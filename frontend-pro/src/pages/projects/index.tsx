import {
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { useModel } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Divider,
  Drawer,
  Empty,
  Form,
  Input,
  List,
  Modal,
  message,
  Popconfirm,
  Row,
  Select,
  Space,
  Statistic,
  Tag,
  Typography,
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  type BusinessLine,
  type EmailMessageSummary,
  type ProjectMember,
  type ProjectTreeNode,
  superworkApi,
  type UserRecord,
} from '@/services/superwork/api';
import '../workbench/style.less';
import './style.less';

type ProjectForm = {
  businessLineId?: number;
  parentId: number | null;
  name: string;
  code: string;
  managerId?: number | null;
  status: number;
};
const STATUS_LABEL: Record<number, string> = {
  0: '已结束',
  1: '进行中',
  2: '待开始',
  3: '已交付',
  4: '运维中',
};
const STATUS_COLOR: Record<number, string> = {
  0: 'default',
  1: 'success',
  2: 'warning',
  3: 'purple',
  4: 'blue',
};
const ROLE_CODES = [
  'admin',
  '系统管理员',
  'DIRECTOR',
  'DEPUTY_DIRECTOR',
  'BUSINESS_OWNER',
  'EFFECTIVENESS_OWNER',
  'BU_ADMIN',
];
const canManageRole = (role?: string) => ROLE_CODES.includes(role || '');

const allNodes = (nodes: ProjectTreeNode[]): ProjectTreeNode[] =>
  nodes.flatMap((node) => [
    node,
    ...(node.children ? allNodes(node.children) : []),
  ]);
const formatDate = (value?: string) =>
  value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—';

export default function ProjectsPage() {
  const { initialState } = useModel('@@initialState');
  const canManage = canManageRole(initialState?.currentUser?.role);
  const [businessLines, setBusinessLines] = useState<BusinessLine[]>([]);
  const [tree, setTree] = useState<ProjectTreeNode[]>([]);
  const [users, setUsers] = useState<UserRecord[]>([]);
  const [businessLineId, setBusinessLineId] = useState<number>();
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<ProjectTreeNode>();
  const [projectDraft, setProjectDraft] = useState<ProjectForm>();
  const [form] = Form.useForm<ProjectForm>();
  const [submitting, setSubmitting] = useState(false);
  const [drawerProject, setDrawerProject] = useState<ProjectTreeNode>();
  const [drawerLoading, setDrawerLoading] = useState(false);
  const [members, setMembers] = useState<ProjectMember[]>([]);
  const [emails, setEmails] = useState<EmailMessageSummary[]>([]);
  const [memberOpen, setMemberOpen] = useState(false);
  const [memberForm] = Form.useForm<{ userId?: number; role?: string }>();
  const [memberSubmitting, setMemberSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [treeData, businessLinePage, userPage] = await Promise.all([
        superworkApi.getProjectTree(businessLineId),
        superworkApi.getBusinessLines({ page: 1, size: 200 }),
        superworkApi.getUsers({ page: 1, size: 200 }),
      ]);
      setTree(treeData || []);
      setBusinessLines(businessLinePage.records || []);
      setUsers(userPage.records || []);
    } catch (e) {
      setTree([]);
      setError(e instanceof Error ? e.message : '项目数据加载失败');
    } finally {
      setLoading(false);
    }
  }, [businessLineId]);
  useEffect(() => {
    void load();
  }, [load]);

  const userMap = useMemo(
    () => new Map(users.map((item) => [item.id, item.realName])),
    [users],
  );
  const businessLineMap = useMemo(
    () => new Map(businessLines.map((item) => [item.id, item.name])),
    [businessLines],
  );
  const filteredGroups = useMemo(() => {
    const query = keyword.trim().toLowerCase();
    return businessLines
      .map((line) => {
        const projects = tree
          .filter((project) => project.businessLineId === line.id)
          .filter((project) => {
            if (!query) return true;
            const haystack = [
              project.name,
              project.code,
              ...(project.children || []).flatMap((child) => [
                child.name,
                child.code,
              ]),
            ]
              .join(' ')
              .toLowerCase();
            return haystack.includes(query);
          });
        return { ...line, projects };
      })
      .filter((group) => group.projects.length > 0);
  }, [businessLines, keyword, tree]);
  const totalCount = allNodes(tree).length;
  const parentOptions = useMemo(
    () => tree.filter((item) => item.children || item.level === 1),
    [tree],
  );

  useEffect(() => {
    if (modalOpen && projectDraft) form.setFieldsValue(projectDraft);
  }, [form, modalOpen, projectDraft]);

  const openCreate = (parent?: ProjectTreeNode, lineId?: number) => {
    setEditing(undefined);
    setMemberOpen(false);
    setProjectDraft({
      businessLineId: lineId ?? parent?.businessLineId,
      parentId: parent?.id ?? null,
      name: '',
      code: '',
      managerId: null,
      status: parent ? 1 : 2,
    });
    setModalOpen(true);
  };
  const openEdit = (project: ProjectTreeNode) => {
    setEditing(project);
    setProjectDraft({
      businessLineId: project.businessLineId,
      parentId: project.parentId,
      name: project.name,
      code: project.code,
      managerId: project.managerId,
      status: project.status,
    });
    setModalOpen(true);
  };
  const submit = async () => {
    const values = await form.validateFields();
    if (values.businessLineId == null) {
      message.error('请选择业务线');
      return;
    }
    setSubmitting(true);
    try {
      if (editing)
        await superworkApi.updateProject(editing.id, {
          ...values,
          businessLineId: values.businessLineId,
          parentId: values.parentId ?? null,
          managerId: values.managerId ?? null,
        });
      else
        await superworkApi.createProject({
          ...values,
          businessLineId: values.businessLineId,
          parentId: values.parentId ?? null,
          managerId: values.managerId ?? null,
        });
      message.success(editing ? '项目已更新' : '项目已创建');
      setModalOpen(false);
      await load();
    } catch (e) {
      message.error(
        e instanceof Error
          ? e.message
          : editing
            ? '更新项目失败'
            : '创建项目失败',
      );
    } finally {
      setSubmitting(false);
    }
  };
  const remove = async (project: ProjectTreeNode) => {
    try {
      await superworkApi.deleteProject(project.id);
      message.success('项目已删除');
      await load();
    } catch (e) {
      message.error(e instanceof Error ? e.message : '删除项目失败');
    }
  };

  const openDrawer = async (project: ProjectTreeNode) => {
    setDrawerProject(project);
    setDrawerLoading(true);
    setMembers([]);
    setEmails([]);
    const [memberResult, emailResult] = await Promise.allSettled([
      superworkApi.getProjectMembers(project.id),
      superworkApi.getEmailMessages({ projectId: project.id, size: 5 }),
    ]);
    if (memberResult.status === 'fulfilled')
      setMembers(memberResult.value || []);
    if (emailResult.status === 'fulfilled')
      setEmails(emailResult.value.records || []);
    setDrawerLoading(false);
  };
  const addMember = async () => {
    if (!drawerProject) return;
    const values = await memberForm.validateFields();
    if (values.userId == null) {
      message.error('请选择成员');
      return;
    }
    setMemberSubmitting(true);
    try {
      await superworkApi.addProjectMember({
        projectId: drawerProject.id,
        userId: values.userId,
        role: values.role || undefined,
      });
      message.success('成员已添加');
      memberForm.resetFields();
      setMemberOpen(false);
      setMembers(await superworkApi.getProjectMembers(drawerProject.id));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '添加成员失败');
    } finally {
      setMemberSubmitting(false);
    }
  };
  const removeMember = async (member: ProjectMember) => {
    if (!drawerProject) return;
    try {
      await superworkApi.removeProjectMember(drawerProject.id, member.userId);
      message.success('成员已移除');
      setMembers(await superworkApi.getProjectMembers(drawerProject.id));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '移除成员失败');
    }
  };

  return (
    <div className="sw-page sw-projects">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">
            FOUNDATION / PROJECTS
          </Typography.Text>
          <Typography.Title level={2}>项目管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            按业务线组织主项目与子项目，详情中保留成员和邮件上下文。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
          {canManage && (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => openCreate()}
            >
              新增项目
            </Button>
          )}
        </Space>
      </div>
      <Row gutter={[16, 16]} className="sw-stat-row">
        <Col xs={24} sm={8}>
          <Card className="sw-stat-card" variant="borderless">
            <Statistic title="项目总数" value={loading ? '-' : totalCount} />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card className="sw-stat-card" variant="borderless">
            <Statistic
              title="业务线"
              value={loading ? '-' : businessLines.length}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card className="sw-stat-card" variant="borderless">
            <Statistic
              title="进行中"
              value={
                loading
                  ? '-'
                  : allNodes(tree).filter((item) => item.status === 1).length
              }
              styles={{ content: { color: '#2563eb' } }}
            />
          </Card>
        </Col>
      </Row>
      <Card variant="borderless" className="sw-filter-card">
        <Space wrap>
          <Select
            allowClear
            value={businessLineId}
            onChange={(value) => setBusinessLineId(value)}
            placeholder="按业务线筛选"
            style={{ width: 190 }}
            options={businessLines.map((item) => ({
              label: item.name,
              value: item.id,
            }))}
          />
          <Input
            allowClear
            value={keyword}
            onChange={(event) => setKeyword(event.target.value)}
            onPressEnter={() => void load()}
            prefix={<SearchOutlined />}
            placeholder="搜索项目名称或编码"
            style={{ width: 260 }}
          />
          <Button
            onClick={() => {
              setBusinessLineId(undefined);
              setKeyword('');
            }}
          >
            重置
          </Button>
        </Space>
      </Card>
      {error && (
        <Alert
          type="error"
          showIcon
          message="无法读取项目数据"
          description={error}
          action={
            <Button size="small" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      )}
      {!loading && !filteredGroups.length ? (
        <Card variant="borderless">
          <Empty description="暂无项目数据" />
        </Card>
      ) : (
        filteredGroups.map((group) => (
          <Card
            key={group.id}
            variant="borderless"
            className="sw-project-group"
            title={
              <Space>
                <Typography.Text strong>{group.name}</Typography.Text>
                <Tag>
                  {group.projects.reduce(
                    (count, project) =>
                      count + 1 + (project.children?.length || 0),
                    0,
                  )}{' '}
                  个项目
                </Tag>
              </Space>
            }
            extra={
              canManage && (
                <Button
                  type="link"
                  icon={<PlusOutlined />}
                  onClick={() => openCreate(undefined, group.id)}
                >
                  新增项目
                </Button>
              )
            }
          >
            <Row gutter={[14, 14]}>
              {group.projects.map((project) => (
                <Col xs={24} md={12} xl={8} key={project.id}>
                  <Card
                    size="small"
                    className="sw-project-card"
                    hoverable
                    onClick={() => void openDrawer(project)}
                  >
                    <div className="sw-project-card-title">
                      <Typography.Text strong ellipsis>
                        {project.name}
                      </Typography.Text>
                      <Tag color={STATUS_COLOR[project.status]}>
                        {STATUS_LABEL[project.status] || '未知'}
                      </Tag>
                    </div>
                    <Typography.Text code>
                      {project.code || '未设置编码'}
                    </Typography.Text>
                    <Typography.Paragraph
                      type="secondary"
                      ellipsis={{ rows: 2 }}
                    >
                      {project.fullPath || project.name}
                    </Typography.Paragraph>
                    <div className="sw-project-card-meta">
                      <span>
                        负责人：
                        {project.managerName ||
                          userMap.get(project.managerId || -1) ||
                          '未分配'}
                      </span>
                      <span>
                        层级：{project.level === 1 ? '主项目' : '子项目'}
                      </span>
                    </div>
                    {project.children?.length ? (
                      <div className="sw-subproject-list">
                        <Typography.Text type="secondary">
                          子项目
                        </Typography.Text>
                        {project.children.map((child) => (
                          <div className="sw-subproject-row" key={child.id}>
                            <Button
                              type="link"
                              size="small"
                              onClick={(event) => {
                                event.stopPropagation();
                                void openDrawer(child);
                              }}
                            >
                              {child.name}
                            </Button>
                            {canManage && (
                              <Space size={0}>
                                <Button
                                  type="text"
                                  size="small"
                                  icon={<EditOutlined />}
                                  aria-label={`编辑${child.name}`}
                                  onClick={(event) => {
                                    event.stopPropagation();
                                    openEdit(child);
                                  }}
                                />
                                <Popconfirm
                                  title={`删除「${child.name}」吗？`}
                                  okText="删除"
                                  cancelText="取消"
                                  okButtonProps={{ danger: true }}
                                  onConfirm={() => void remove(child)}
                                >
                                  <Button
                                    type="text"
                                    danger
                                    size="small"
                                    icon={<DeleteOutlined />}
                                    aria-label={`删除${child.name}`}
                                    onClick={(event) => event.stopPropagation()}
                                  />
                                </Popconfirm>
                              </Space>
                            )}
                          </div>
                        ))}
                      </div>
                    ) : null}
                    <Divider style={{ margin: '12px 0' }} />
                    {canManage && (
                      <Space>
                        <Button
                          type="link"
                          size="small"
                          icon={<PlusOutlined />}
                          onClick={(event) => {
                            event.stopPropagation();
                            openCreate(project);
                          }}
                        >
                          子项目
                        </Button>
                        <Button
                          type="link"
                          size="small"
                          icon={<EditOutlined />}
                          onClick={(event) => {
                            event.stopPropagation();
                            openEdit(project);
                          }}
                        >
                          编辑
                        </Button>
                        <Popconfirm
                          title={`删除「${project.name}」吗？`}
                          description={
                            project.children?.length
                              ? '存在子项目时后端会拒绝删除。'
                              : undefined
                          }
                          okText="删除"
                          cancelText="取消"
                          okButtonProps={{ danger: true }}
                          onConfirm={() => void remove(project)}
                        >
                          <Button
                            type="link"
                            danger
                            size="small"
                            icon={<DeleteOutlined />}
                            onClick={(event) => event.stopPropagation()}
                          >
                            删除
                          </Button>
                        </Popconfirm>
                      </Space>
                    )}
                  </Card>
                </Col>
              ))}
            </Row>
          </Card>
        ))
      )}
      <Modal
        title={
          editing
            ? '编辑项目'
            : projectDraft?.parentId
              ? '新增子项目'
              : '新增项目'
        }
        open={modalOpen}
        forceRender
        onCancel={() => setModalOpen(false)}
        onOk={() => void submit()}
        okText="保存"
        cancelText="取消"
        confirmLoading={submitting}
        destroyOnHidden
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="businessLineId"
            label="业务线"
            rules={[{ required: true, message: '请选择业务线' }]}
          >
            <Select
              disabled={Boolean(projectDraft?.parentId)}
              options={businessLines.map((item) => ({
                label: item.name,
                value: item.id,
              }))}
              placeholder="请选择业务线"
            />
          </Form.Item>
          <Form.Item name="parentId" label="父项目">
            <Select
              allowClear
              disabled={Boolean(editing)}
              options={parentOptions
                .filter(
                  (item) =>
                    item.id !== editing?.id &&
                    (!projectDraft?.businessLineId ||
                      item.businessLineId === projectDraft.businessLineId),
                )
                .map((item) => ({ label: item.name, value: item.id }))}
              placeholder="留空为主项目"
            />
          </Form.Item>
          <Form.Item
            name="name"
            label="项目名称"
            rules={[{ required: true, message: '请输入项目名称' }]}
          >
            <Input maxLength={120} />
          </Form.Item>
          <Form.Item
            name="code"
            label="项目编码"
            rules={[{ required: true, message: '请输入项目编码' }]}
          >
            <Input maxLength={80} />
          </Form.Item>
          <Form.Item name="managerId" label="项目负责人">
            <Select
              allowClear
              options={users.map((item) => ({
                label: item.realName,
                value: item.id,
              }))}
              placeholder="请选择负责人"
            />
          </Form.Item>
          <Form.Item name="status" label="状态" rules={[{ required: true }]}>
            <Select
              options={Object.entries(STATUS_LABEL).map(([value, label]) => ({
                label,
                value: Number(value),
              }))}
            />
          </Form.Item>
        </Form>
      </Modal>
      <Drawer
        title={drawerProject?.name || '项目详情'}
        size={520}
        open={Boolean(drawerProject)}
        onClose={() => setDrawerProject(undefined)}
      >
        {drawerProject && (
          <>
            <Descriptions bordered column={1} size="small">
              <Descriptions.Item label="项目编码">
                {drawerProject.code || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="完整路径">
                {drawerProject.fullPath || drawerProject.name}
              </Descriptions.Item>
              <Descriptions.Item label="业务线">
                {businessLineMap.get(drawerProject.businessLineId) || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="项目负责人">
                {drawerProject.managerName ||
                  userMap.get(drawerProject.managerId || -1) ||
                  '未分配'}
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={STATUS_COLOR[drawerProject.status]}>
                  {STATUS_LABEL[drawerProject.status] || '未知'}
                </Tag>
              </Descriptions.Item>
            </Descriptions>
            <Divider />
            <div className="sw-drawer-section">
              <div className="sw-drawer-section-title">
                <Typography.Title level={5}>项目成员</Typography.Title>
                {canManage && (
                  <Button
                    type="link"
                    icon={<PlusOutlined />}
                    onClick={() => setMemberOpen((value) => !value)}
                  >
                    {memberOpen ? '取消添加' : '添加成员'}
                  </Button>
                )}
              </div>
              {memberOpen && (
                <Form
                  form={memberForm}
                  layout="inline"
                  onFinish={() => void addMember()}
                >
                  <Form.Item
                    name="userId"
                    rules={[{ required: true, message: '请选择用户' }]}
                  >
                    <Select
                      showSearch
                      optionFilterProp="label"
                      placeholder="选择用户"
                      style={{ width: 170 }}
                      options={users
                        .filter(
                          (user) =>
                            !members.some(
                              (member) => member.userId === user.id,
                            ),
                        )
                        .map((user) => ({
                          label: user.realName,
                          value: user.id,
                        }))}
                    />
                  </Form.Item>
                  <Form.Item name="role">
                    <Input placeholder="角色（选填）" />
                  </Form.Item>
                  <Button
                    type="primary"
                    htmlType="submit"
                    loading={memberSubmitting}
                  >
                    确定
                  </Button>
                </Form>
              )}
              <List
                loading={drawerLoading}
                locale={{ emptyText: '暂无项目成员' }}
                dataSource={members}
                renderItem={(member) => (
                  <List.Item
                    actions={
                      canManage
                        ? [
                            <Popconfirm
                              key="remove"
                              title="移除该成员吗？"
                              okText="移除"
                              cancelText="取消"
                              okButtonProps={{ danger: true }}
                              onConfirm={() => void removeMember(member)}
                            >
                              <Button type="link" danger>
                                移除
                              </Button>
                            </Popconfirm>,
                          ]
                        : undefined
                    }
                  >
                    <List.Item.Meta
                      title={
                        member.realName ||
                        userMap.get(member.userId) ||
                        member.username ||
                        `用户${member.userId}`
                      }
                      description={member.role || '未设置角色'}
                    />
                  </List.Item>
                )}
              />
            </div>
            <Divider />
            <div className="sw-drawer-section">
              <div className="sw-drawer-section-title">
                <Typography.Title level={5}>相关邮件</Typography.Title>
                <Typography.Text type="secondary">
                  近期开启的项目往来
                </Typography.Text>
              </div>
              <List
                loading={drawerLoading}
                locale={{ emptyText: '暂无相关邮件' }}
                dataSource={emails}
                renderItem={(mail) => (
                  <List.Item>
                    <List.Item.Meta
                      title={mail.subject || '（无主题）'}
                      description={`${formatDate(mail.receivedAt)} · ${mail.preview || '暂无预览'}`}
                    />
                  </List.Item>
                )}
              />
            </div>
          </>
        )}
      </Drawer>
    </div>
  );
}
