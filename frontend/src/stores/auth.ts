import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, type KeyMatterAccess } from '@/utils/api'

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
  const keyMatterAccess = ref<KeyMatterAccess | null>(null)
  let keyMatterAccessRequest: {
    promise: Promise<KeyMatterAccess>
    generation: number
  } | null = null
  let keyMatterAccessGeneration = 0

  const deniedKeyMatterAccess = (): KeyMatterAccess => ({
    canAccess: false,
    canManageAll: false,
    canFeedbackOwn: false
  })

  const resetKeyMatterAccess = () => {
    keyMatterAccessGeneration += 1
    keyMatterAccess.value = null
    keyMatterAccessRequest = null
  }

  const loadKeyMatterAccess = (
    force = false,
    shouldCommit: () => boolean = () => true
  ): Promise<KeyMatterAccess> => {
    let request = keyMatterAccessRequest
    if (!request) {
      if (!force && keyMatterAccess.value) return Promise.resolve(keyMatterAccess.value)

      const generation = keyMatterAccessGeneration
      const rawPromise = api.getKeyMatterAccess()
        .catch(() => deniedKeyMatterAccess())
      request = { promise: rawPromise, generation }
      keyMatterAccessRequest = request
      void rawPromise.finally(() => {
        if (keyMatterAccessRequest === request) keyMatterAccessRequest = null
      })
    }

    return request.promise.then(access => {
      if (request.generation !== keyMatterAccessGeneration || !shouldCommit()) {
        return deniedKeyMatterAccess()
      }
      keyMatterAccess.value = access
      return access
    })
  }

  const isLoggedIn = () => !!token.value

  const login = async (username: string, password: string) => {
    try {
      const response = await api.login(username, password)

      resetKeyMatterAccess()
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

      resetKeyMatterAccess()
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
    resetKeyMatterAccess()
    token.value = null
    user.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    localStorage.removeItem('refreshToken')
  }

  const initAuth = () => {
    const storedToken = localStorage.getItem('token')
    const storedUser = localStorage.getItem('user')
    if (storedToken !== token.value) resetKeyMatterAccess()
    token.value = storedToken
    user.value = storedToken && storedUser ? JSON.parse(storedUser) : null
  }

  return {
    token,
    user,
    keyMatterAccess,
    isLoggedIn,
    loadKeyMatterAccess,
    login,
    register,
    logout,
    initAuth
  }
})
