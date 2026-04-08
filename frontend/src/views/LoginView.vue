<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const loginMethod = ref<'account' | 'sms'>('account')
const loading = ref(false)
const smsCooldown = ref(0)
const loginError = ref('')

const form = reactive({
  username: '',
  password: '',
  remember: false,
  phone: '',
  code: ''
})

const errors = reactive({
  username: '',
  password: '',
  phone: '',
  code: ''
})

let smsTimer: ReturnType<typeof setInterval> | null = null

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

const validateSms = () => {
  let valid = true
  errors.phone = ''
  errors.code = ''

  if (!form.phone) {
    errors.phone = '请输入手机号'
    valid = false
  } else if (!/^1[3-9]\d{9}$/.test(form.phone)) {
    errors.phone = '手机号格式不正确'
    valid = false
  }
  if (!form.code) {
    errors.code = '请输入验证码'
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
    router.push('/requirements')
  } else {
    errors.password = result.error || '用户名或密码错误'
  }
}

const sendSms = () => {
  if (smsCooldown.value > 0) return

  errors.phone = ''
  if (!form.phone) {
    errors.phone = '请输入手机号'
    return
  }
  if (!/^1[3-9]\d{9}$/.test(form.phone)) {
    errors.phone = '手机号格式不正确'
    return
  }

  smsCooldown.value = 60
  smsTimer = setInterval(() => {
    smsCooldown.value--
    if (smsCooldown.value <= 0) {
      if (smsTimer) clearInterval(smsTimer)
    }
  }, 1000)
}

const handleSmsLogin = async () => {
  if (!validateSms()) return

  loading.value = true
  setTimeout(() => {
    loading.value = false
    router.push('/requirements')
  }, 1500)
}

const handleWechatWork = () => {
  alert('企微登录功能开发中...')
}

const handleWechatScan = () => {
  alert('微信扫码登录功能开发中...')
}
</script>

<template>
  <div class="login-page">
    <div class="login-layout">
      <!-- 左侧品牌区 -->
      <div class="brand-section">
        <div class="brand-content">
          <div class="brand-logo">
            <div class="brand-logo-icon">📋</div>
            <span class="brand-logo-text">电商BU管理系统</span>
          </div>

          <div class="brand-headline">
            <h1>需求全生命周期<br>管理平台</h1>
            <p>统一管理从需求评估到交付验收的完整流程，提升团队协作效率</p>
          </div>

          <div class="brand-features">
            <div class="brand-feature">
              <div class="brand-feature-icon">✓</div>
              <span>需求全生命周期追踪</span>
            </div>
            <div class="brand-feature">
              <div class="brand-feature-icon">✓</div>
              <span>项目与任务协同管理</span>
            </div>
            <div class="brand-feature">
              <div class="brand-feature-icon">✓</div>
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
          <p>请选择登录方式</p>
        </div>

        <!-- 登录方式切换 -->
        <div class="login-tabs">
          <button
            class="login-tab"
            :class="{ active: loginMethod === 'account' }"
            @click="loginMethod = 'account'"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/>
              <circle cx="12" cy="7" r="4"/>
            </svg>
            账号密码
          </button>
          <button
            class="login-tab"
            :class="{ active: loginMethod === 'sms' }"
            @click="loginMethod = 'sms'"
          >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"/>
            </svg>
            手机验证码
          </button>
        </div>

        <!-- 账号密码登录 -->
        <div class="login-form-wrap">
          <form
            class="login-form"
            :class="{ active: loginMethod === 'account' }"
            @submit.prevent="handleLogin"
          >
            <div class="form-content">
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
                  <span>记住我</span>
                </label>
                <a href="#" class="forgot-link">忘记密码？</a>
              </div>

              <button type="submit" class="submit-btn" :disabled="loading">
                {{ loading ? '登录中...' : '登 录' }}
              </button>
            </div>
          </form>

          <!-- 手机验证码登录 -->
          <form
            class="login-form"
            :class="{ active: loginMethod === 'sms' }"
            @submit.prevent="handleSmsLogin"
          >
            <div class="form-content">
              <div class="form-item" :class="{ error: errors.phone }">
                <label>手机号</label>
                <input
                  v-model="form.phone"
                  type="tel"
                  placeholder="请输入手机号"
                  maxlength="11"
                >
                <div v-if="errors.phone" class="error-text">
                  <span>⚠</span> {{ errors.phone }}
                </div>
              </div>

              <div class="sms-row" :class="{ error: errors.code }">
                <div class="form-item">
                  <label>验证码</label>
                  <input
                    v-model="form.code"
                    type="text"
                    placeholder="请输入验证码"
                    maxlength="6"
                  >
                </div>
                <button
                  type="button"
                  class="sms-btn"
                  @click="sendSms"
                  :disabled="smsCooldown > 0"
                >
                  {{ smsCooldown > 0 ? smsCooldown + 's' : '获取验证码' }}
                </button>
              </div>
              <div v-if="errors.code" class="error-text" style="margin-top: -12px; margin-bottom: 12px;">
                <span>⚠</span> {{ errors.code }}
              </div>

              <div class="form-footer" style="visibility: hidden;">
                <label class="remember-label">
                  <input type="checkbox" disabled>
                  <span>记住我</span>
                </label>
                <a href="#" class="forgot-link">忘记密码？</a>
              </div>

              <button type="submit" class="submit-btn" :disabled="loading">
                {{ loading ? '登录中...' : '登 录' }}
              </button>
            </div>
          </form>
        </div>

        <!-- 微信登录分隔线 -->
        <div class="wechat-section">
          <div class="divider">
            <span>其他登录方式</span>
          </div>

          <div class="wechat-btns">
            <button type="button" class="wechat-btn enterprise" @click="handleWechatWork">
              <svg viewBox="0 0 24 24" fill="currentColor">
                <path d="M8.5 11a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3zm5 0a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z"/>
                <path d="M12 2C6.477 2 2 6.026 2 11c0 2.824 1.373 5.343 3.535 7.042l-.622 2.79a.5.5 0 0 0 .694.552l3.016-1.506C9.252 20.465 10.582 21 12 21c5.523 0 10-4.026 10-9s-4.477-10-10-10zm-4.5 14.5a2.5 2.5 0 1 1 0-5 2.5 2.5 0 0 1 0 5zm9 0a2.5 2.5 0 1 1 0-5 2.5 2.5 0 0 1 0 5z"/>
              </svg>
              企微登录
            </button>
            <button type="button" class="wechat-btn scan" @click="handleWechatScan">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="3" y="3" width="7" height="7"/>
                <rect x="14" y="3" width="7" height="7"/>
                <rect x="3" y="14" width="7" height="7"/>
                <rect x="14" y="14" width="7" height="7"/>
              </svg>
              微信扫码
            </button>
          </div>
        </div>
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
  font-size: 24px;
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

/* 登录表单容器 */
.form-content {
  min-height: 240px;
}

.login-form-wrap {
  position: relative;
  width: 100%;
}

.login-form {
  width: 100%;
  position: absolute;
  top: 0;
  left: 0;
  opacity: 0;
  visibility: hidden;
  transition: opacity 0.2s ease, visibility 0.2s ease;
}

.login-form.active {
  position: relative;
  opacity: 1;
  visibility: visible;
}

/* 登录方式切换 */
.login-tabs {
  display: flex;
  background: #F1F5F9;
  border-radius: 12px;
  padding: 4px;
  margin-bottom: 32px;
}

.login-tab {
  flex: 1;
  padding: 12px 16px;
  text-align: center;
  font-size: 14px;
  font-weight: 500;
  color: #64748B;
  background: transparent;
  border: none;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.login-tab:hover {
  color: #2563EB;
}

.login-tab.active {
  background: #FFFFFF;
  color: #2563EB;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.login-tab svg {
  width: 18px;
  height: 18px;
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

/* 手机号登录 */
.sms-row {
  display: flex;
  gap: 12px;
  align-items: flex-end;
}

.sms-row .form-item {
  flex: 1;
  margin-bottom: 0;
}

.sms-btn {
  height: 48px;
  padding: 0 20px;
  background: #F1F5F9;
  border: 1.5px solid #E2E8F0;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 500;
  color: #2563EB;
  cursor: pointer;
  transition: all 0.2s ease;
  white-space: nowrap;
}

.sms-btn:hover {
  background: #E2E8F0;
}

.sms-btn:disabled {
  color: #94A3B8;
  cursor: not-allowed;
}

/* 微信登录选项 */
.wechat-section {
  margin-bottom: 24px;
}

.divider {
  display: flex;
  align-items: center;
  gap: 16px;
  margin: 24px 0;
}

.divider::before,
.divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: #E2E8F0;
}

.divider span {
  font-size: 13px;
  color: #94A3B8;
}

.wechat-btns {
  display: flex;
  gap: 12px;
}

.wechat-btn {
  flex: 1;
  height: 48px;
  background: #FFFFFF;
  border: 1.5px solid #E2E8F0;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 500;
  color: #374151;
}

.wechat-btn:hover {
  border-color: #2563EB;
  color: #2563EB;
  background: #F8FAFC;
}

.wechat-btn svg {
  width: 20px;
  height: 20px;
}

.wechat-btn.enterprise svg {
  color: #07C160;
}

.wechat-btn.scan svg {
  color: #07C160;
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
