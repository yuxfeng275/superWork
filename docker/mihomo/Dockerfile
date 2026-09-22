# mihomo 出口代理镜像（本地二进制构建）：
# Docker Hub 在 241 拉不动 metacubex/mihomo；改为宿主机预置二进制 + 本地 alpine 缓存构建。
# 获取二进制（在能访问 GitHub 的机器上执行）：
#   curl -sL -o mihomo.gz https://github.com/MetaCubeX/mihomo/releases/download/v1.19.31/mihomo-linux-amd64-v1.19.31.gz
#   gunzip -f mihomo.gz && chmod +x mihomo && rsync -az mihomo 241:.../docker/mihomo/
FROM alpine:3.21

# 订阅节点走 ss+obfs（TCP，无需 CA 证书）；不装额外包，构建零网络依赖
COPY mihomo /usr/local/bin/mihomo

ENTRYPOINT ["mihomo", "-d", "/root/.config/mihomo"]
