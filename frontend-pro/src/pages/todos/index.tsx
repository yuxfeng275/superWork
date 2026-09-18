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
import { useCallback, useEffect, useState } from "react";
import { history } from "@umijs/max";
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

export default function TodosPage() {
  const [rows, setRows] = useState<UserTodo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [actingId, setActingId] = useState<number>();

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setRows((await superworkApi.getTodos()) || []);
    } catch (e) {
      setRows([]);
      setError(e instanceof Error ? e.message : "待办加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

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

  return (
    <div className="sw-page sw-todos">
      <div className="sw-page-header">
        <div>
          <Typography.Text className="sw-eyebrow">WORK / TODOS</Typography.Text>
          <Typography.Title level={2}>待办</Typography.Title>
          <Typography.Paragraph type="secondary">
            周进展和周会里被 @ 到的事项会汇到这里，处理后可标记完成。
          </Typography.Paragraph>
        </div>
        <Button icon={<ReloadOutlined />} onClick={() => void load()}>
          刷新
        </Button>
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
            emptyText: <Empty description="暂无被 @ 的待办" />,
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
                  {row.status !== "DONE" && (
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
