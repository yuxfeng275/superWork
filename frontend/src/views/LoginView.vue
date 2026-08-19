<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const loading = ref(false)
const loginError = ref('')

const REMEMBER_USERNAME_KEY = 'bu-remembered-username'

const form = reactive({
  username: '',
  password: '',
  remember: false
})

const errors = reactive({
  username: '',
  password: ''
})

onMounted(() => {
  const remembered = localStorage.getItem(REMEMBER_USERNAME_KEY)
  if (remembered) {
    form.username = remembered
    form.remember = true
  }
})

const validateAccount = () => {
  let valid = true
  errors.username = ''
  errors.password = ''

  if (!form.username) {
    errors.username = '请输入用户名'
    valid = false
  }
  if (!form.password) {
    errors.password = '请输入密码'
    valid = false
  }
  return valid
}

const handleLogin = async () => {
  if (!validateAccount()) return

  loading.value = true
  loginError.value = ''

  const result = await authStore.login(form.username, form.password)

  loading.value = false

  if (result.success) {
    if (form.remember) {
      localStorage.setItem(REMEMBER_USERNAME_KEY, form.username)
    } else {
      localStorage.removeItem(REMEMBER_USERNAME_KEY)
    }
    router.push('/requirements')
  } else {
    errors.password = result.error || '用户名或密码错误'
  }
}

const handleForgotPassword = () => {
  ElMessage.info('请联系系统管理员重置密码')
}
</script>

<template>
  <div class="login-page">
    <div class="login-layout">
      <!-- 左侧品牌区 -->
      <div class="brand-section">
        <div class="brand-content">
          <div class="brand-logo">
            <div class="brand-logo-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <rect x="8" y="2" width="8" height="4" rx="1" />
                <path d="M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2" />
                <path d="m9 12 2 2 4-4" />
              </svg>
            </div>
            <span class="brand-logo-text">电商BU管理系统</span>
          </div>

          <div class="brand-headline">
            <h1>需求全生命周期<br>管理平台</h1>
            <p>统一管理从需求评估到交付验收的完整流程，提升团队协作效率</p>
          </div>

          <div class="brand-features">
            <div class="brand-feature">
              <div class="brand-feature-icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12" /></svg>
              </div>
              <span>需求全生命周期追踪</span>
            </div>
            <div class="brand-feature">
              <div class="brand-feature-icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12" /></svg>
              </div>
              <span>项目与任务协同管理</span>
            </div>
            <div class="brand-feature">
              <div class="brand-feature-icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12" /></svg>
              </div>
              <span>多维度数据统计看板</span>
            </div>
          </div>
        </div>

        <div class="brand-footer">
          © 2026 电商BU内部管理系统
        </div>
      </div>

      <!-- 右侧登录表单区 -->
      <div class="form-section">
        <div class="form-header">
          <h2>欢迎回来</h2>
          <p>使用账号密码登录系统</p>
        </div>

        <form class="login-form" @submit.prevent="handleLogin">
          <div class="form-item" :class="{ error: errors.username }">
            <label>用户名</label>
            <input
              v-model="form.username"
              type="text"
              placeholder="请输入用户名"
              autocomplete="username"
            >
            <div v-if="errors.username" class="error-text">
              <span>⚠</span> {{ errors.username }}
            </div>
          </div>

          <div class="form-item" :class="{ error: errors.password || loginError }">
            <label>密码</label>
            <input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              autocomplete="current-password"
            >
            <div v-if="errors.password || loginError" class="error-text">
              <span>⚠</span> {{ errors.password || loginError }}
            </div>
          </div>

          <div class="form-footer">
            <label class="remember-label">
              <input v-model="form.remember" type="checkbox">
              <span>记住用户名</span>
            </label>
            <button type="button" class="forgot-link" @click="handleForgotPassword">忘记密码？</button>
          </div>

          <button type="submit" class="submit-btn" :disabled="loading">
            {{ loading ? '登录中...' : '登 录' }}
          </button>
        </form>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(15, 23, 42, 0.6);
  position: relative;
}

.login-page::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: url('https://images.unsplash.com/photo-1486406146926-c627a92ad1ab?w=1920&q=80') center/cover no-repeat;
  z-index: -1;
}

.login-layout {
  display: flex;
  width: 1000px;
  max-width: calc(100vw - 32px);
  min-height: 600px;
  background: #FFFFFF;
  border-radius: 24px;
  box-shadow: 0 25px 80px rgba(0, 0, 0, 0.3);
  overflow: hidden;
  position: relative;
  z-index: 1;
}

/* 左侧品牌区 */
.brand-section {
  flex: 1;
  background: linear-gradient(135deg, #2563EB 0%, #1D4ED8 100%);
  padding: 60px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  color: white;
  position: relative;
  overflow: hidden;
}

.brand-section::before {
  content: '';
  position: absolute;
  top: -50%;
  right: -50%;
  width: 100%;
  height: 100%;
  background: radial-gradient(circle, rgba(255,255,255,0.1) 0%, transparent 70%);
  border-radius: 50%;
}

.brand-content {
  position: relative;
  z-index: 1;
}

.brand-logo {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 48px;
}

.brand-logo-icon {
  width: 48px;
  height: 48px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.brand-logo-icon svg {
  width: 24px;
  height: 24px;
}

.brand-logo-text {
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.5px;
}

.brand-headline h1 {
  font-size: 36px;
  font-weight: 700;
  line-height: 1.2;
  margin-bottom: 16px;
  letter-spacing: -1px;
}

.brand-headline p {
  font-size: 16px;
  opacity: 0.85;
  line-height: 1.6;
}

.brand-features {
  margin-top: 48px;
}

.brand-feature {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  font-size: 14px;
  opacity: 0.9;
}

.brand-feature-icon {
  width: 32px;
  height: 32px;
  background: rgba(255, 255, 255, 0.15);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.brand-feature-icon svg {
  width: 16px;
  height: 16px;
}

.brand-footer {
  font-size: 12px;
  opacity: 0.6;
  position: relative;
  z-index: 1;
}

/* 右侧登录表单区 */
.form-section {
  flex: 1;
  padding: 60px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-height: 600px;
}

.form-header {
  margin-bottom: 32px;
}

.form-header h2 {
  font-size: 28px;
  font-weight: 700;
  color: #1E293B;
  margin-bottom: 8px;
  letter-spacing: -0.5px;
}

.form-header p {
  font-size: 15px;
  color: #64748B;
}

.login-form {
  width: 100%;
}

/* 表单样式 */
.form-item {
  margin-bottom: 20px;
}

.form-item label {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: #374151;
  margin-bottom: 8px;
}

.form-item input {
  width: 100%;
  height: 48px;
  padding: 0 16px;
  border: 1.5px solid #E2E8F0;
  border-radius: 12px;
  font-size: 15px;
  color: #1E293B;
  transition: all 0.2s ease;
  background: #FFFFFF;
  box-sizing: border-box;
}

.form-item input::placeholder {
  color: #94A3B8;
}

.form-item input:focus {
  outline: none;
  border-color: #2563EB;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
}

.form-item.error input {
  border-color: #DC2626;
  box-shadow: 0 0 0 3px rgba(220, 38, 38, 0.1);
}

.error-text {
  font-size: 12px;
  color: #DC2626;
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 4px;
}

/* 提交按钮 */
.submit-btn {
  width: 100%;
  height: 52px;
  background: linear-gradient(135deg, #2563EB 0%, #1D4ED8 100%);
  color: white;
  border: none;
  border-radius: 12px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s ease;
  margin-top: 8px;
}

.submit-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 24px rgba(37, 99, 235, 0.3);
}

.submit-btn:active {
  transform: translateY(0);
}

.submit-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

/* 底部选项 */
.form-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 20px;
}

.remember-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #64748B;
  cursor: pointer;
}

.remember-label input {
  width: 16px;
  height: 16px;
  accent-color: #2563EB;
}

.forgot-link {
  font-size: 14px;
  color: #2563EB;
  text-decoration: none;
  font-weight: 500;
  background: none;
  border: none;
  cursor: pointer;
  padding: 0;
}

.forgot-link:hover {
  text-decoration: underline;
}

/* 响应式 */
@media (max-width: 900px) {
  .login-layout {
    max-width: 100%;
    min-height: 100vh;
    border-radius: 0;
    flex-direction: column;
    box-shadow: none;
  }

  .brand-section {
    padding: 40px;
    min-height: auto;
  }

  .brand-features {
    display: none;
  }

  .form-section {
    padding: 40px;
  }
}
</style>
