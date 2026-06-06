// ─── Perfil (GET /me, GET /users/:username, GET /users/by-id/:userId) ───

export interface UserProfile {
  id: string
  username: string
  name: string
  headline: string | null
  email: string
  company: string | null
  location: string | null
  avatar: string | null
  banner: string | null
  bio: string | null
  skills: string[]
  goals: string[]
  availability: string | null
  portfolio: string | null
  externalLinks: string[]
  openToWork: boolean
  isMentor: boolean
  connectionsCount: number
  followersCount: number
  reputation: number
}

// ─── Atualizar Perfil (PATCH /me/profile) ───

export interface UpdateProfileRequest {
  name?: string
  headline?: string | null
  company?: string | null
  location?: string | null
  avatar?: string | null
  banner?: string | null
  bio?: string | null
  skills?: string[]
  goals?: string[]
  availability?: string | null
  portfolio?: string | null
  externalLinks?: string[]
  openToWork?: boolean
  isMentor?: boolean
}

// ─── Post (GET /feed, GET /users/:username/posts) ───

export interface Post {
  id: string
  authorId: string
  authorUsername: string | null
  authorName: string | null
  authorAvatar: string | null
  type: 'INSIGHT' | 'ARTICLE' | 'QUESTION' | 'PROJECT' | 'OPPORTUNITY'
  content: string
  tags: string[]
  mediaIds: string[]
  createdAt: string
  counters: { reactions: number; comments: number; saves: number; shares: number }
  communityId?: string | null
}

// ─── Experiências (/me/experiences) ───

export interface Experience {
  id: string
  title: string
  company: string
  location: string | null
  description: string | null
  currentlyHere: boolean
  startDate: string | null
  endDate: string | null
}

export interface ExperienceRequest {
  title: string
  company: string
  location?: string | null
  description?: string | null
  startDate?: string | null
  endDate?: string | null
}

// ─── Educação (/me/education) ───

export interface Education {
  id: string
  school: string
  degree: string
  field: string
  description: string | null
  currentlyHere: boolean
  startDate: string | null
  endDate: string | null
}

export interface EducationRequest {
  school: string
  degree: string
  field: string
  description?: string | null
  startDate?: string | null
  endDate?: string | null
}

// ─── Certificações (/me/certifications) ───

export interface Certification {
  id: string
  name: string
  issuer: string
  description: string | null
  issueDate: string | null
  expirationDate: string | null
  credentialUrl: string | null
}

export interface CertificationRequest {
  name: string
  issuer: string
  description?: string | null
  issueDate?: string | null
  expirationDate?: string | null
  credentialUrl?: string | null
}

// ─── Rede Social / Contatos ───

export interface NetworkUser {
  id: string
  username: string | null
  name: string | null
  headline: string | null
  avatar: string | null
  connections: number
  followers: number
  connected: boolean
}

export interface NetworkSummary {
  userId: string
  connections: number
  followers: number
  pendingRequests: number
}

export interface ConnectionRequest {
  id: string
  fromUserId: string
  toUserId: string
  status: string
  createdAt: string
}

// ─── Mensagens ───

export interface Conversation {
  id: string
  userId: string
  kind: 'DIRECT' | 'GROUP'
  title: string | null
  participantIds: string[]
  lastMessage: string | null
  time: string | null
  unread: number
}

export interface ConversationDetail {
  id: string
  kind: string
  title: string | null
  participantIds: string[]
  lastMessage: string | null
  unread: number
}

export interface Message {
  id: string
  conversationId: string
  from: string
  text: string
  time: string | null
}

export interface CreateConversationRequest {
  kind: 'DIRECT' | 'GROUP'
  title?: string | null
  participantIds: string[]
}

export interface SendMessageRequest {
  text: string
}

// ─── Busca ───

export interface SearchResult {
  id: string
  type: string
  title: string
  subtitle: string | null
  image: string | null
  href: string | null
}

// ─── Comunidades ───

export interface Community {
  id: string
  name: string
  description: string
  members: number
  topic: string | null
  cover: string | null
  joined: boolean
}

export interface CreateCommunityRequest {
  name: string
  description: string
  topic?: string | null
  cover?: string | null
}

// ─── Vagas ───

export interface Job {
  id: string
  title: string
  company: string
  logo: string | null
  location: string | null
  type: 'CLT' | 'PJ' | 'INTERN' | 'FREELANCE'
  level: 'JUNIOR' | 'MID' | 'SENIOR' | 'LEAD'
  salary: string | null
  skills: string[]
  postedAt: string | null
  matchScore: number
}

export interface CreateJobRequest {
  title: string
  company: string
  logo?: string | null
  location?: string | null
  type: string
  level: string
  salary?: string | null
  skills: string[]
}

// ─── Eventos ───

export interface Event {
  id: string
  title: string
  date: string
  time: string
  format: string
  host: string
  attendees: number
  cover: string | null
}

export interface CreateEventRequest {
  title: string
  date: string
  time: string
  format: string
  host: string
  cover?: string | null
}

// ─── Mentoria ───

export interface Mentor {
  id: string
  userId: string
  expertise: string[]
  rating: number
  sessions: number
  price: string | null
  nextSlot: string | null
}

export interface MentorSlot {
  id: string
  startsAt: string | null
  endsAt: string | null
  available: boolean
}

export interface MentorSessionRequest {
  slotId: string
  message?: string | null
}

export interface CreateMentorRequest {
  expertise: string[]
  price?: string | null
}

export interface CreateSlotRequest {
  startsAt: string  // ISO instant "2025-08-15T14:00:00Z"
  endsAt: string    // ISO instant "2025-08-15T15:00:00Z"
}

// ─── Paginação (cursor) ───

export interface FeedPage<T> {
  items: T[]
  nextCursor: string | null
}

// ─── Membro da comunidade ───

export interface MemberView {
  userId: string
  username: string | null
  name: string | null
  avatar: string | null
  role: 'ADMIN' | 'MEMBER'
}
