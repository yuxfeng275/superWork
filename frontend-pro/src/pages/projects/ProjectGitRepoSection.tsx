import { PlusOutlined } from '@ant-design/icons';
import {
  Button,
  Form,
  Input,
  List,
  message,
  Popconfirm,
  Select,
  Space,
  Switch,
  Tag,
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  type ProjectGitRepo,
  type ProjectGitRepoSavePayload,
  superworkApi,
} from '@/services/superwork/api';
import {
  AGENT_OPTIONS,
  type GitRepoFormValues,
  normalizeGitRepoForm,
  validateGitRepoForm,
} from './gitRepoForm';

const EMPTY_FORM: GitRepoFormValues = {
  name: undefined,
  repoUrl: undefined,
  defaultBranch: undefined,
  workDir: undefined,
  agent: 'dsh',
  credentialRef: undefined,
  enabled: true,
};

const repoTitle = (repo: ProjectGitRepo) => repo.name || repo.repoUrl;

const repoDescription = (repo: ProjectGitRepo) => {
  const base = `${repo.repoUrl} · 分支 ${repo.defaultBranch || 'main'} · agent ${repo.agent || 'dsh'}`;
  return repo.workDir ? `${base} · 目录 ${repo.workDir}` : base;
};

/**
 * 项目详情抽屉里的「代码仓库」区块：查看 / 绑定 / 编辑 / 解绑 HTTPS 仓库。
 * 夜间代码类任务会落到这些仓库上。
 */
export default function ProjectGitRepoSection({
  projectId,
  canManage,
}: {
  projectId: number;
  canManage: boolean;
}) {
  const [repos, setRepos] = useState<ProjectGitRepo[]>([]);
  const [loading, setLoading] = useState(false);
  const [formOpen, setFormOpen] = useState(false);
  const [editingId, setEditingId] = useState<number>();
  const [submitting, setSubmitting] = useState(false);
  const [form] = Form.useForm<GitRepoFormValues>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await superworkApi.listProjectGitRepos(projectId);
      setRepos(data ?? []);
    } catch (error) {
      message.error(
        error instanceof Error ? error.message : '加载代码仓库失败，请稍后重试',
      );
    } finally {
      setLoading(false);
    }
  }, [projectId]);

  useEffect(() => {
    void load();
  }, [load]);

  const openCreate = () => {
    setEditingId(undefined);
    form.setFieldsValue(EMPTY_FORM);
    setFormOpen(true);
  };

  const openEdit = (repo: ProjectGitRepo) => {
    setEditingId(repo.id);
    form.setFieldsValue({
      name: repo.name,
      repoUrl: repo.repoUrl,
      defaultBranch: repo.defaultBranch,
      workDir: repo.workDir,
      agent: repo.agent || 'dsh',
      credentialRef: repo.credentialRef,
      enabled: repo.enabled !== 0,
    });
    setFormOpen(true);
  };

  const submit = async () => {
    const values = form.getFieldsValue(true) as GitRepoFormValues;
    const errors = validateGitRepoForm(values);
    if (errors.length > 0) {
      message.error(errors[0].message);
      return;
    }
    const normalized = normalizeGitRepoForm(values);
    const payload: ProjectGitRepoSavePayload = {
      id: editingId,
      name: normalized.name,
      repoUrl: normalized.repoUrl,
      defaultBranch: normalized.defaultBranch,
      workDir: normalized.workDir,
      agent: normalized.agent,
      credentialRef: normalized.credentialRef,
      enabled: normalized.enabledFlag === 1,
    };
    setSubmitting(true);
    try {
      await superworkApi.saveProjectGitRepo(projectId, payload);
      message.success(editingId === undefined ? '仓库已绑定' : '仓库已更新');
      setFormOpen(false);
      setEditingId(undefined);
      form.resetFields();
      await load();
    } catch (error) {
      message.error(
        error instanceof Error ? error.message : '保存失败，请稍后重试',
      );
    } finally {
      setSubmitting(false);
    }
  };

  const unbind = async (repo: ProjectGitRepo) => {
    try {
      await superworkApi.deleteProjectGitRepo(projectId, repo.id);
      message.success('已解绑');
      await load();
    } catch (error) {
      message.error(
        error instanceof Error ? error.message : '解绑失败，请稍后重试',
      );
    }
  };

  return (
    <div>
      {canManage && (
        <Space style={{ marginBottom: 12 }}>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            绑定仓库
          </Button>
        </Space>
      )}
      {canManage && formOpen && (
        <Form
          form={form}
          layout="vertical"
          initialValues={EMPTY_FORM}
          style={{ maxWidth: 520, marginBottom: 12 }}
        >
          <Form.Item label="名称" name="name">
            <Input placeholder="选填，默认取仓库地址" allowClear />
          </Form.Item>
          <Form.Item label="仓库地址" name="repoUrl">
            <Input placeholder="https://git.example.com/group/repo.git" allowClear />
          </Form.Item>
          <Form.Item label="默认分支" name="defaultBranch">
            <Input placeholder="main" allowClear />
          </Form.Item>
          <Form.Item label="工作目录" name="workDir">
            <Input placeholder="sql/" allowClear />
          </Form.Item>
          <Form.Item label="Agent" name="agent">
            <Select options={AGENT_OPTIONS} />
          </Form.Item>
          <Form.Item label="凭据引用" name="credentialRef">
            <Input placeholder="选填，凭据标识而非明文" allowClear />
          </Form.Item>
          <Form.Item label="启用" name="enabled" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Space>
            <Button type="primary" loading={submitting} onClick={() => void submit()}>
              保存
            </Button>
            <Button
              onClick={() => {
                setFormOpen(false);
                setEditingId(undefined);
                form.resetFields();
              }}
            >
              取消
            </Button>
          </Space>
        </Form>
      )}
      <List
        loading={loading}
        locale={{ emptyText: '暂无绑定仓库' }}
        dataSource={repos}
        renderItem={(repo) => (
          <List.Item
            actions={
              canManage
                ? [
                    <Button key="edit" type="link" onClick={() => openEdit(repo)}>
                      编辑
                    </Button>,
                    <Popconfirm
                      key="unbind"
                      title="解绑该仓库吗？"
                      okText="解绑"
                      cancelText="取消"
                      okButtonProps={{ danger: true }}
                      onConfirm={() => void unbind(repo)}
                    >
                      <Button type="link" danger>
                        解绑
                      </Button>
                    </Popconfirm>,
                  ]
                : undefined
            }
          >
            <List.Item.Meta
              title={
                <>
                  {repoTitle(repo)}
                  {repo.enabled === 0 && (
                    <Tag style={{ marginLeft: 8 }}>已停用</Tag>
                  )}
                </>
              }
              description={repoDescription(repo)}
            />
          </List.Item>
        )}
      />
    </div>
  );
}
