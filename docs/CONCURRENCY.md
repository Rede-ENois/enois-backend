# Concurrency and hot rows

How writes that many people make at the same time (reactions on a popular post, messages in one
conversation, a notification that groups events) stay correct on MariaDB without errors, deadlocks or
lost updates. Read this before writing code that changes a counter or any row shared between users.

## Isolation level: READ COMMITTED

Every pooled connection runs at READ COMMITTED
(`spring.datasource.hikari.transaction-isolation` in `application.properties`, mirrored in the test
properties). MariaDB and MySQL default to REPEATABLE READ.

Why: since MariaDB 11.6 (`innodb_snapshot_isolation=ON` by default), a REPEATABLE READ transaction
that writes a row another transaction changed after its first read fails with *Record has changed
since last read*. Almost every request reads before it writes, so two people interacting with the same
post, notification or profile turned into HTTP 500s. READ COMMITTED gives each statement the latest
committed data, does not take gap locks (fewer deadlocks and lock waits), and is the default of
PostgreSQL and Oracle. It needs `binlog_format` ROW or MIXED (the MariaDB default) when binary logging
is on.

What READ COMMITTED does not do: protect a read-decide-write sequence. Two transactions can still
read the same value and both write a result computed from it. The rules below cover that.

## Rules

1. **Counters change in SQL, relative to the stored value.** `update ... set x = x + :delta`, one
   statement, never "load the entity, add one, save it". The post counters (`reactions`, `comments`,
   `saves`, `shares`, `reposts`) are `updatable = false` on `PostJpaEntity`: only
   `PostRepository#adjustCounter` changes them, so saving a post loaded earlier never rolls them
   back. `@DynamicUpdate` on the entity keeps an edit from rewriting columns it did not change.
2. **Read-decide-write on a shared row locks the row first.** If the decision depends on the current
   state ("did this person already react?", "what is the group count now?"), take a row lock before
   reading: `PostRepository#lockForUpdate` (post interactions), the `for update` read of the unread
   notification group (`NotificationRepository#findUnreadGroup`).
3. **Lock the parent before inserting the child.** Inserting a row with a foreign key takes a shared
   lock on the parent row. Two transactions that each insert a child and then update the parent
   (insert a reaction, then bump the post counter; insert a message, then touch the conversation)
   hold the shared lock and wait for each other's exclusive one: a deadlock. Write the parent first
   (`lockForUpdate` for posts, `MessagingRepository#touchConversation` for conversations) and they
   queue instead.
4. **Write only the columns you own.** A setting saved by its own screen (the name style) is a
   targeted `update` of its columns, not a save of the whole `users` row.
5. **Idempotent bulk updates filter on the state they change** (`... and is_read = false`), so they
   touch and lock only rows that still need the change.
6. **Do not catch a failed write inside a transaction to carry on.** An exception that crosses a
   Spring Data repository (or any `@Transactional` bean) marks the whole transaction rollback-only:
   catching it ends the request in `UnexpectedRollbackException` at commit. Make the write report
   what it did instead (`insert ignore` returning 0, a `delete` returning the row count), or prevent
   the race with rule 2.
7. **No second connection while holding one.** Work that runs after a commit in its own transaction
   (the daily presence) goes to an executor (`presenceExecutor`). Opening a `REQUIRES_NEW` from
   `afterCommit` keeps the request's connection while asking for another, and a burst of requests
   can hold the whole pool waiting for itself.

## Where each rule lives

| Flow | Hot row | How it is written |
|---|---|---|
| React, save, share, repost, comment (and undo) | `posts` | `lockForUpdate`, child row, `adjustCounter` |
| Delete or moderate a comment | `posts` | `lockForUpdate`, then load and remove the comment, `adjustCounter` |
| Accepted answer cleared on comment removal | `posts` | `update ... where id = :postId and accepted_comment_id = :commentId` |
| React to a comment | `comment_reactions` | `insert ignore` / `delete` with row counts |
| Send a message | `conversations` | `touchConversation` first |
| Join a grouped notification | `notifications` | `select ... for update` of the unread group |
| Mark notifications read | `notifications` | `update ... where ... and is_read = false` |
| Name style | `users` | `updateNameStyle` (only its columns) |
| Notification preferences | `users` | `lockForUpdate` before reading and merging the partial update; also serializes the first preferences row |
| Daily presence | treasury account | `presenceExecutor`, one thread |
| Any coin movement | `coin_accounts` (treasury, payer, payee) | `lockAccount` (`for update`) before the balance check |
| Community project mutations | `community_projects` | `findByIdForUpdate` before edit, approve, reject, suspend, resubmit, unsuspend or remove; public reads remain unlocked; publication and its first outbox event commit together |
| Daily game prizes | treasury account | one transaction per credit, up to 3 attempts on `COIN_CONCURRENCY` or a lock timeout |

## Testing

`CommunityProjectPublicationConcurrencyTests` uses real service and JPA transactions in threads,
latches and H2's `INFORMATION_SCHEMA.SESSIONS` (`SESSION_STATE = 'BLOCKED'`). It exercises edit and
approval in both orders, checks that every existing-project mutation locks before reading state,
and verifies that public search completes while approval holds the lock.

`PostCounterConcurrencyTests` and `GroupedNotificationTests#concurrentJoinsOfOneGroupCountEveryEvent`
run the same requests from several threads at once against H2 and assert exact counts. H2 does not
reproduce MariaDB's snapshot errors or its foreign-key lock upgrades, so a new hot-row flow also
needs a check against MariaDB (the `docker-compose.yml` `hml` profile).
