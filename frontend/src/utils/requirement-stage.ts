export interface RequirementStageAction {
  type: string
  label: string
  class: string
}

interface StageActionOptions {
  status?: string
  type?: string
  hasEvaluation?: boolean
}

export const getRequirementStageActions = ({
  status,
  type,
  hasEvaluation
}: StageActionOptions): RequirementStageAction[] => {
  const actions: RequirementStageAction[] = []

  switch (status) {
    case '待评估':
      actions.push(
        { type: 'start_eval', label: '开始评估', class: 'primary' },
        { type: 'reject', label: '标记已拒绝', class: 'danger' }
      )
      break
    case '评估中':
      actions.push(
        { type: 'submit_eval', label: hasEvaluation ? '更新评估' : '提交评估', class: 'primary' },
        { type: 'plan_design', label: '配置设计', class: 'secondary' }
      )
      if (hasEvaluation) {
        actions.push(
          { type: 'approve', label: '评估通过', class: 'primary' },
          { type: 'reject', label: '标记已拒绝', class: 'danger' }
        )
        if (type === '项目需求') {
          actions.push({ type: 'convert', label: '转为产品需求', class: 'secondary' })
        }
      }
      break
    case '待设计':
      actions.push(
        { type: 'plan_design', label: '配置设计', class: 'secondary' },
        { type: 'start_design', label: '开始设计', class: 'primary' }
      )
      break
    case '待确认':
      if (type === '项目需求') {
        actions.push({ type: 'customer_confirm', label: '客户确认', class: 'primary' })
      } else {
        actions.push({ type: 'internal_confirm', label: '内部确认', class: 'primary' })
      }
      break
    case '开发中':
      actions.push(
        { type: 'add_task', label: '添加任务', class: 'secondary' },
        { type: 'start_test', label: '提测', class: 'primary' }
      )
      break
    case '测试中':
      actions.push(
        { type: 'test_pass', label: '测试通过', class: 'primary' },
        { type: 'test_fail', label: '测试失败', class: 'danger' }
      )
      break
    case '待上线':
      actions.push({ type: 'go_online', label: '确认上线', class: 'primary' })
      break
    case '已上线':
      if (type === '项目需求') {
        actions.push({ type: 'deliver', label: '确认交付', class: 'primary' })
      }
      break
    case '已交付':
      if (type === '项目需求') {
        actions.push({ type: 'accept', label: '确认验收', class: 'primary' })
      }
      break
  }

  return actions
}
