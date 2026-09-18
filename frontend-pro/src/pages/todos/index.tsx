import { CheckOutlined, ReloadOutlined } from "@ant-design/icons";
import {
  Alert,
  Button,
  Card,
  Empty,
  Space,
  Table,
  Tag,
  Typography,
} from "antd";
import dayjs from "dayjs";
import { useCallback, useEffect, useMemo, useState } from "react";
import { history, useLocation, useModel } from "@umijs/max";
import {
  superworkApi,
  type UserTodo,
} from "@/services/superwork/api";
import "../workbench/style.less";
import "./style.less";

const statusLabel: Record<string, { text: string; color: string }> = {
  OPEN: { text: "待处理", color: "gold" },
  DONE: { text: "已完成", color: "green" },
};

const searchParams = (search: string) => new URLSearchParams(search);

export default function TodosPage() {
  const location = useLocation();
  const { initialState } = useModel("@@initialState");
  const params = useMemo(() => searchParams(location.search), [location.search]);
  const userId = Number(params.get("userId") || "") || undefined;
  const userToken = params.get("user") || undefined;
  const viewingOthers = Boolean(userId || userToken);
  const [rows, setRows] = useState<UserTodo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [actingId, setActingId] = useState<number>();

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setRows(
        (await superworkApi.getTodos({
          userId,
          user: userId ? undefined : userToken,
        })) || []
      );
    } catch (e) {
      setRows([]);
      setError(e instanceof Error ? e.message : "待办加载失败");
    } finally {
      setLoading(false);
    }
  }, [userId, userToken]);

  useEffect(() => {
    void load();
  }, [load]);

  const complete = async (row: UserTodo) => {
    setActingId(row.id);
    try {
      await superworkApi.completeTodo(row.id);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "完成待办失败");
    } finally {
      setActingId(undefined);
    }
  };

  const currentUserId = Number(initialState?.currentUser?.id);
  const title = viewingOthers
    ? `${userToken || "该同事"}的待办`
    : "待办";

  return (
    <div className="sw-page sw-todos">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">WORK / TODOS</Typography.Text>
          <Typography.Title level={2}>{title}</Typography.Title>
          <Typography.Paragraph type="secondary">
            {viewingOthers
              ? "来自周进展 / 周会中的 @ 提及。"
              : "周进展和周会里被 @ 到的事项会汇到这里，处理后可标记完成。"}
          </Typography.Paragraph>
        </div>
        <Space>
          {viewingOthers && (
            <Button onClick={() => history.push("/todos")}>我的待办</Button>
          )}
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>
            刷新
          </Button>
        </Space>
      </div>
      {error && (
        <Alert type="error" showIcon message={error} style={{ marginBottom: 16 }} />
      )}
      <Card variant="borderless">
        <Table
          rowKey="id"
          loading={loading}
          dataSource={rows}
          pagination={false}
          locale={{
            emptyText: (
              <Empty
                description={viewingOthers ? "该同事暂无待办" : "暂无被 @ 的待办"}
              />
            ),
          }}
          columns={[
            {
              title: "待办",
              dataIndex: "title",
              render: (value, row) => (
                <Space orientation="vertical" size={2}>
                  <Typography.Text strong>{value}</Typography.Text>
                  <Typography.Text type="secondary">
                    {row.excerpt || "—"}
                  </Typography.Text>
                </Space>
              ),
            },
            {
              title: "状态",
              dataIndex: "status",
              width: 110,
              render: (value) => (
                <Tag color={statusLabel[value]?.color || "default"}>
                  {statusLabel[value]?.text || value}
                </Tag>
              ),
            },
            {
              title: "时间",
              dataIndex: "createdAt",
              width: 180,
              render: (value) =>
                value ? dayjs(value).format("YYYY-MM-DD HH:mm") : "—",
            },
            {
              title: "操作",
              width: 160,
              render: (_, row) => (
                <Space>
                  {row.link && (
                    <Button type="link" onClick={() => history.push(row.link!)}>
                      查看
                    </Button>
                  )}
                  {row.status !== "DONE" &&
                    !viewingOthers &&
                    row.assigneeId === currentUserId && (
                    <Button
                      type="link"
                      icon={<CheckOutlined />}
                      loading={actingId === row.id}
                      onClick={() => void complete(row)}
                    >
                      完成
                    </Button>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </Card>
    </div>
  );
}
