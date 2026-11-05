# Social and professional interfaces

One account, two interfaces. The **social** interface is community life (feed, communities, groups,
DMs); the **professional** interface is work (jobs, applications, freelances, companies, services,
mentoring). Both run on the same account and session; switching never logs out and never creates an
account. The professional identity is the existing `corporate_profiles` row (see
`implementation/corporate-profile-decisions.md`): its own `@`, name, avatar and status
(`DRAFT`/`ACTIVE`/`DISABLED`).

## What the backend enforces

The interface is a frontend concept; the **identity of each operation is decided here, from the
session**, never from the payload.

| Operation | Identity | Without an active professional profile |
| --- | --- | --- |
| `POST /jobs/{id}/applications` | professional (snapshot `identity=CORPORATE`, professional `@`, name, headline, avatar) | `403 NO_ACTIVE_CORPORATE_PROFILE`, nothing stored or delivered |
| `POST /freelances/{id}/proposals` (new) | professional, delivered in a `FREELANCE` conversation | `403 NO_ACTIVE_CORPORATE_PROFILE` |
| `POST /feed/posts` with `type=BUSINESS` | professional (unchanged) | `403 NO_ACTIVE_CORPORATE_PROFILE` |
| LinkedIn auto-share | only posts with `authorIdentity=CORPORATE` | social posts are never shared |

`CorporateProfileService.requireActive(userId)` is the single guard for professional actions.

The application snapshot never carries the social `@` or social avatar. The social name (`nome
social`) stays: it is a personal right, not social-profile data. Account values (skills, experience,
resume, verification) are the same for both identities, as before.

## Conversations: context from the kind

No new column, no backfill. `ConversationContext.of(kind)`:

- `PROFESSIONAL`: `JOB` (application), `FREELANCE` (proposal), `SERVICE` (hiring).
- `SOCIAL`: `DIRECT`, `GROUP`.

Each 1:1 kind is its own thread (`findPairByKind`): a social DM never receives an application and an
application never lands in the social DM. Before this change, an application reused an existing
`DIRECT` thread and a social DM could reuse a `JOB` one.

API additions (all optional, older clients keep working):

- `GET /conversations`, `/conversations/archived`, `/conversations/search`,
  `/conversations/unread-count`: `?context=SOCIAL|PROFESSIONAL` (blank = all, anything else = 400
  `INVALID_CONTEXT`).
- Conversation views carry `context` and `identities` (`{userId: {identity, username, name,
  avatar}}`): in professional threads, the active professional identity of each participant, so the
  other side sees the professional `@`, never the social one.
- `POST /conversations` with `kind=DIRECT` and one participant now returns the existing DM instead of
  creating a duplicate (the inbox search used to create one per click).

Messages in professional threads notify as `NEW_WORK_MESSAGE` (push category `MESSAGES`).

## Notifications

`NotificationContext.of(kind)`: `JOB_APPLICATION_STATUS`, `NEW_WORK_MESSAGE` and `MICROPROJETO` are
professional; security, moderation, sanctions, appeals, support and platform notices are shared (both
interfaces); the rest is social. `GET /notifications?context=` filters in SQL, and the view carries
`context` and `actorIdentity` (professional notifications show the actor's active professional
identity).

## Public professional page

`GET /corporate/{username}` adds the public portfolio of the account: `identityVerified`,
`memberSince`, `skills`, `experiences`, `educations`, `certifications`. It never returns the social
`@`, name or avatar, nor reputation, followers or connections (not mixed between identities).

## History and migration

Nothing is rewritten. Old applications keep their snapshot (`identity=SOCIAL` ones still link to
`/perfil/{username}`), old conversations keep their kind and therefore their inbox. From now on, a
status update of an application whose thread was a `DIRECT` one goes to a new `JOB` thread; the old
thread stays where it was. No database migration.

## Known gaps

- Publishing a job, a freelance or a service does not require the professional identity yet (only
  applying and proposing do), and job, freelance and service listings still show the poster's social
  `@`. Changing who may publish is a product decision.
- The application snapshot still includes the account reputation (recruiters use it); whether it
  should stay is a product decision.
