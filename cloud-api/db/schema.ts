import { index, integer, sqliteTable, text, uniqueIndex } from "drizzle-orm/sqlite-core";

export const users = sqliteTable("users", {
  id: text("id").primaryKey(),
  email: text("email").notNull(),
  passwordSalt: text("password_salt").notNull(),
  passwordHash: text("password_hash").notNull(),
  createdAt: integer("created_at").notNull(),
}, (table) => ({ emailUnique: uniqueIndex("users_email_unique").on(table.email) }));

export const sessions = sqliteTable("sessions", {
  tokenHash: text("token_hash").primaryKey(),
  userId: text("user_id").notNull().references(() => users.id, { onDelete: "cascade" }),
  createdAt: integer("created_at").notNull(),
  expiresAt: integer("expires_at").notNull(),
  revokedAt: integer("revoked_at"),
}, (table) => ({ userExpiry: index("sessions_user_expiry_idx").on(table.userId, table.expiresAt) }));

export const userState = sqliteTable("user_state", {
  userId: text("user_id").primaryKey().references(() => users.id, { onDelete: "cascade" }),
  revision: integer("revision").notNull(),
  payloadJson: text("payload_json").notNull(),
  updatedAt: integer("updated_at").notNull(),
});

export const authRateLimits = sqliteTable("auth_rate_limits", {
  keyHash: text("key_hash").primaryKey(),
  windowStartedAt: integer("window_started_at").notNull(),
  attempts: integer("attempts").notNull(),
}, (table) => ({ windowIndex: index("auth_rate_limits_window_idx").on(table.windowStartedAt) }));
