import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/utils/api'

export interface User {
  id: number
  username: string
  realName: string
  role: string
  email?: string
  phone?: string
  avatar?: string
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem('token'))
  const user = ref<User | null>(null)

  const isLoggedIn = () => !!token.value

  const login = async (username: string, password: string) => {
    try {
      const response = await api.login(username, password)

      token.value = response.accessToken
      user.value = {
        id: response.userInfo.id,
        username: response.userInfo.username,
        realName: response.userInfo.realName,
        role: response.userInfo.role,
        email: response.userInfo.email,
        phone: response.userInfo.phone
      }

      localStorage.setItem('token', response.accessToken)
      localStorage.setItem('user', JSON.stringify(user.value))
      localStorage.setItem('refreshToken', response.refreshToken)

      return { success: true }
    } catch (error: any) {
      return { success: false, error: error.message || '登录失败' }
    }
  }

  const register = async (data: {
    username: string
    password: string
    realName: string
    role: string
    email?: string
    phone?: string
  }) => {
    try {
      const response = await api.register(data)

      token.value = response.accessToken
      user.value = {
        id: response.userInfo.id,
        username: response.userInfo.username,
        realName: response.userInfo.realName,
        role: response.userInfo.role,
        email: response.userInfo.email,
        phone: response.userInfo.phone
      }

      localStorage.setItem('token', response.accessToken)
      localStorage.setItem('user', JSON.stringify(user.value))
      localStorage.setItem('refreshToken', response.refreshToken)

      return { success: true }
    } catch (error: any) {
      return { success: false, error: error.message || '注册失败' }
    }
  }

  const logout = () => {
    token.value = null
    user.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    localStorage.removeItem('refreshToken')
  }

  const initAuth = () => {
    const storedToken = localStorage.getItem('token')
    const storedUser = localStorage.getItem('user')
    if (storedToken && storedUser) {
      token.value = storedToken
      user.value = JSON.parse(storedUser)
    }
  }

  return {
    token,
    user,
    isLoggedIn,
    login,
    register,
    logout,
    initAuth
  }
})
