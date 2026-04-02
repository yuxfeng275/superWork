# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

电商BU内部管理系统 - 需求全生命周期管理平台

**核心业务：**
- 管理需求全生命周期：评估 → 设计 → 确认 → 开发 → 测试 → 上线 → 交付 → 验收
- 组织架构：业务线 → 项目（支持父子项目）→ 团队成员
- 工时管理：预估工时 vs 实际工时对比分析
- 数据统计：人效分析、业务线健康度、需求全景、进度对比

**重要文档：**
- 完整设计文档：`docs/superpowers/specs/2026-04-01-bu-management-system-design.md`
- 实施计划：`/Users/yufeng/.claude/plans/ethereal-skipping-star.md`

## Architecture

**Monorepo Structure (npm workspaces):**
```
├── backend/     # Express + TypeScript + Prisma + PostgreSQL
├── frontend/    # React + TypeScript + Vite + Ant Design
└── docs/        # 设计文档和实施计划
```

**Backend Architecture:**
- Express REST API with JWT authentication
- Prisma ORM with PostgreSQL adapter pattern (`@prisma/adapter-pg`)
- Controller → Service → Repository pattern
- RBAC + 数据权限隔离（按项目）

**Frontend Architecture:**
- React 18 with TypeScript
- Zustand for state management
- React Router v6 for routing
- Ant Design 5.x for UI components

**Database:**
- PostgreSQL 15+ on remote server (192.168.1.241)
- Prisma migrations for schema management
- 15+ tables covering: 组织架构、需求管理、任务工时、事项管理、工作流配置

## Development Commands

**Install dependencies:**
```bash
npm install
```

**Development (both frontend and backend):**
```bash
npm run dev
```

**Development (separate):**
```bash
npm run dev:backend   # Backend only (http://localhost:3000)
npm run dev:frontend  # Frontend only (http://localhost:5173)
```

**Build:**
```bash
npm run build              # Build all workspaces
npm run build --workspace=backend
npm run build --workspace=frontend
```

**Testing:**
```bash
npm run test               # Run all tests
npm run test --workspace=backend
npm run test:watch --workspace=backend
npm run test:coverage --workspace=backend
```

**Linting:**
```bash
npm run lint               # Lint all workspaces
npm run lint --workspace=backend
npm run lint:fix --workspace=backend
```

**Database:**
```bash
cd backend
npx prisma migrate dev     # Create and apply migration
npx prisma migrate deploy  # Apply migrations (production)
npx prisma generate        # Generate Prisma Client
npx prisma studio          # Open Prisma Studio
npx prisma db push         # Push schema without migration (dev only)
```

**Format:**
```bash
npm run format             # Format all files with Prettier
```

## Key Technical Decisions

**Current Implementation Status:**
- ⚠️ **IMPORTANT**: Current implementation is simplified and does NOT match the complete design document
- Current: 5 tables (User, Requirement, Comment, Attachment, ActivityLog)
- Design requires: 15+ tables with full business workflow
- See `/Users/yufeng/.claude/plans/ethereal-skipping-star.md` for rebuild plan

**Technology Stack Mismatch:**
- Design document specifies: Vue 3 + Spring Boot + MySQL
- Current implementation: React + Express + PostgreSQL
- Decision pending: Keep current stack and update design, or rebuild with original stack

**Prisma Adapter Pattern:**
- Using `@prisma/adapter-pg` with `pg` driver (Prisma 7.x pattern)
- Connection configured in `backend/src/lib/prisma.ts`

**Authentication:**
- JWT tokens with bcryptjs password hashing
- Middleware: `backend/src/middleware/auth.ts`
- Utils: `backend/src/utils/jwt.ts`

## Critical Files

**Backend:**
- `backend/prisma/schema.prisma` - Database schema (needs expansion)
- `backend/src/app.ts` - Express app configuration
- `backend/src/index.ts` - Server entry point
- `backend/src/middleware/auth.ts` - JWT authentication middleware
- `backend/src/routes/*.ts` - API route definitions
- `backend/src/controllers/*.ts` - Request handlers

**Frontend:**
- `frontend/src/main.tsx` - React entry point
- `frontend/src/App.tsx` - Root component
- `frontend/vite.config.ts` - Vite configuration

**Configuration:**
- `package.json` - Root workspace configuration
- `backend/.env` - Backend environment variables (not in git)
- `backend/.env.example` - Environment variable template

## Database Schema Evolution

**Current Schema (Simplified):**
- User, Requirement, Comment, Attachment, ActivityLog

**Required Schema (Per Design Document):**
- 组织架构: BusinessLine, Project, ProjectMember, CustomerContact
- 需求管理: Requirement, RequirementEvaluation, RequirementDesign, DesignWorkLog, RequirementConfirmation, RequirementDelivery
- 任务工时: Task, WorkLog
- 事项管理: Issue
- 工作流: WorkflowConfig
- 附件: Attachment

**Migration Strategy:**
When implementing full schema, use Prisma migrations to version control schema changes.

## Workflow States

**需求生命周期 (Requirement Lifecycle):**

项目需求: 创建 → 待评估 → 评估中 → [已拒绝|待设计] → 设计中 → 待确认(客户) → 开发中 → 测试中 → 待上线 → 已上线 → 已交付 → 已验收

产品需求: 创建 → 待评估 → 评估中 → [已拒绝|待设计] → 设计中 → 待确认(内部) → 开发中 → 测试中 → 待上线 → 已上线

**设计完成条件:**
- 需求可能需要 1-3 种设计工作（原型/UI/技术方案）
- 所有指定的设计工作都标记为"已完成"后，系统自动将需求流转到"待确认"

## Permission Model

**RBAC Roles:**
- BU负责人: 全局数据访问
- 项目经理: 按项目隔离
- 技术经理: 按项目隔离（通过 project_member 关联）
- 产品经理: 按业务线
- 研发/测试/UI设计: 按任务分配

**Data Isolation:**
- 项目经理和技术经理只能访问其参与的项目数据
- 通过 `project_member` 表关联用户和项目

## Remote Server

**Database Server:**
- IP: 192.168.1.241
- User: openclaw
- SSH key authentication configured
- PostgreSQL and Redis installed directly (not Docker)

## Code Style

- TypeScript strict mode enabled
- ESLint + Prettier configured
- Husky + lint-staged for pre-commit hooks
- Conventional Commits for commit messages

## Testing Strategy

- Unit tests: Jest + ts-jest
- Integration tests: Supertest for API testing
- E2E tests: Playwright (planned)
- Minimum coverage: 80%

## API Design

**Response Format:**
```typescript
{
  success: boolean
  data?: T
  error?: string
  meta?: {
    total: number
    page: number
    limit: number
  }
}
```

**Authentication:**
- Header: `Authorization: Bearer <token>`
- All protected routes use `authenticate` middleware

**Current Endpoints:**
- POST `/api/auth/register` - User registration
- POST `/api/auth/login` - User login
- POST `/api/requirements` - Create requirement
- GET `/api/requirements` - List requirements (with filters, search, pagination)
- GET `/api/requirements/:id` - Get requirement details
- PATCH `/api/requirements/:id` - Update requirement
- DELETE `/api/requirements/:id` - Delete requirement

## Implementation Stages

**Planned Stages (See rebuild plan):**
1. Stage 1: 基础设施和组织架构 (2-3 days)
2. Stage 2: 需求创建和评估流程 (2-3 days)
3. Stage 3: 需求设计流程 (2-3 days)
4. Stage 4: 任务和工时管理 (2-3 days)
5. Stage 5: 事项管理 (1-2 days)
6. Stage 6: 工作流引擎 (2-3 days)
7. Stage 7: 数据统计看板 (3-4 days)

**Current Status:**
- Phase 1 (项目初始化) completed
- Phase 2 (数据库配置) completed with simplified schema
- Phase 3 (后端 API) partially completed (auth + basic requirements CRUD)
- **Rebuild required** to match full design specification

## Important Notes

1. **Before implementing new features**: Check the design document to ensure alignment with the complete business requirements
2. **Database changes**: Always use Prisma migrations, never manual SQL
3. **API changes**: Update both controller and route files
4. **Authentication**: All business endpoints must use `authenticate` middleware
5. **Error handling**: Use try-catch with proper error messages
6. **Immutability**: Always return new objects, never mutate existing ones
7. **Type safety**: Leverage Prisma's generated types for database operations
