import { request } from './client';
import type { User } from './types';

export const updateProfile = (input: { fullName: string; email: string }) =>
  request<User>('/api/v1/users/me', { method: 'PUT', auth: true, body: input });

export const changePassword = (input: { oldPassword: string; newPassword: string; confirmPassword: string }) =>
  request<void>('/api/v1/users/me/change-password', { method: 'PUT', auth: true, body: input });
