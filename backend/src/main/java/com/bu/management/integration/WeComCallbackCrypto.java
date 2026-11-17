package com.bu.management.integration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 企业微信回调的签名与加解密（WXBizMsgCrypt 等价实现）。
 *
 * <p>仅在配置了 {@code wecom.callback.token / aes-key / corp-id} 时启用；未配置时
 * {@link #configured()} 为 false，回调端点返回 503，由运维补齐（不影响长连接机器人）。
 *
 * <p>协议口径：
 * <ul>
 *   <li>签名 = SHA1(sorted(token, timestamp, nonce, encrypt) 拼接) 的小写 hex；</li>
 *   <li>AES-256-CBC，key = Base64(aesKey + "=")，IV = key 前 16 字节，PKCS#7 补位；</li>
 *   <li>明文 = random(16) + msgLen(4, 大端) + msg + receiveId。</li>
 * </ul>
 *
 * @author BU Team
 * @since 2026-11-18
 */
@Slf4j
@Service
public class WeComCallbackCrypto {

    /** 企业微信要求 EncodingAESKey 为 43 位 Base64 字符（解码后 32 字节）。 */
    private static final int AES_KEY_CHARS = 43;

    @Value("${wecom.callback.token:}")
    private String token = "";

    @Value("${wecom.callback.aes-key:}")
    private String aesKey = "";

    @Value("${wecom.callback.corp-id:}")
    private String corpId = "";

    /** 企业 CorpID（被动回复的 FromUserName 需要）。 */
    public String corpId() {
        return corpId;
    }

    /** 三项都配置才算启用；缺一项就明确降级，不做「半配置猜着跑」。 */
    public boolean configured() {
        return StringUtils.hasText(token)
                && StringUtils.hasText(aesKey)
                && StringUtils.hasText(corpId)
                && aesKey.trim().length() == AES_KEY_CHARS;
    }

    /**
     * 校验回调签名。
     *
     * @param signature 企微传来的 msg_signature
     * @param timestamp 时间戳
     * @param nonce     随机串
     * @param encrypt   密文（GET 验证时为 echostr）
     */
    public boolean verify(String signature, String timestamp, String nonce, String encrypt) {
        if (!configured() || !StringUtils.hasText(signature)) {
            return false;
        }
        String expected = sign(timestamp, nonce, encrypt);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    /** 按企微规则计算签名（token/timestamp/nonce/encrypt 字典序拼接后 SHA1）。 */
    public String sign(String timestamp, String nonce, String encrypt) {
        String[] parts = {token, nullSafe(timestamp), nullSafe(nonce), nullSafe(encrypt)};
        Arrays.sort(parts);
        String joined = String.join("", parts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-1", e);
        }
    }

    /**
     * 解密企微密文。
     *
     * @param encrypt Base64 密文
     * @return 明文 XML（已校验 receiveId）
     * @throws IllegalArgumentException 密文/补位/receiveId 不合法
     */
    public String decrypt(String encrypt) {
        requireConfigured();
        try {
            byte[] key = aesKeyBytes();
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(Arrays.copyOfRange(key, 0, 16)));
            byte[] decrypted = cipher.doFinal(Base64.getDecoder().decode(encrypt));
            byte[] plain = unpad(decrypted);
            if (plain.length < 20) {
                throw new IllegalArgumentException("企微密文长度不足");
            }
            int msgLen = ((plain[16] & 0xff) << 24) | ((plain[17] & 0xff) << 16)
                    | ((plain[18] & 0xff) << 8) | (plain[19] & 0xff);
            if (msgLen < 0 || 20 + msgLen > plain.length) {
                throw new IllegalArgumentException("企微密文消息长度非法");
            }
            String message = new String(plain, 20, msgLen, StandardCharsets.UTF_8);
            String receiveId = new String(plain, 20 + msgLen, plain.length - 20 - msgLen,
                    StandardCharsets.UTF_8);
            if (!corpId.equals(receiveId)) {
                throw new IllegalArgumentException("企微回调 receiveId 不匹配");
            }
            return message;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("企微回调解密失败：" + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 加密被动回复。
     *
     * @param message 回复 XML
     * @param random16 16 字节随机串（ASCII）
     * @return Base64 密文
     */
    public String encrypt(String message, String random16) {
        requireConfigured();
        if (random16 == null || random16.getBytes(StandardCharsets.UTF_8).length != 16) {
            throw new IllegalArgumentException("random16 必须是 16 字节");
        }
        try {
            byte[] msg = message.getBytes(StandardCharsets.UTF_8);
            byte[] corp = corpId.getBytes(StandardCharsets.UTF_8);
            byte[] raw = new byte[16 + 4 + msg.length + corp.length];
            System.arraycopy(random16.getBytes(StandardCharsets.UTF_8), 0, raw, 0, 16);
            raw[16] = (byte) (msg.length >>> 24);
            raw[17] = (byte) (msg.length >>> 16);
            raw[18] = (byte) (msg.length >>> 8);
            raw[19] = (byte) msg.length;
            System.arraycopy(msg, 0, raw, 20, msg.length);
            System.arraycopy(corp, 0, raw, 20 + msg.length, corp.length);
            byte[] padded = pad(raw);
            byte[] key = aesKeyBytes();
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(Arrays.copyOfRange(key, 0, 16)));
            return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
        } catch (Exception e) {
            throw new IllegalStateException("企微回调解密/加密失败", e);
        }
    }

    private byte[] aesKeyBytes() {
        return Base64.getDecoder().decode(aesKey.trim() + "=");
    }

    private void requireConfigured() {
        if (!configured()) {
            throw new IllegalStateException("企微回调未配置");
        }
    }

    /** PKCS#7 补位到 32 字节块（与企微 WXBizMsgCrypt 一致）。 */
    private static byte[] pad(byte[] raw) {
        int block = 32;
        int amount = block - (raw.length % block);
        if (amount == 0) {
            amount = block;
        }
        byte[] out = Arrays.copyOf(raw, raw.length + amount);
        Arrays.fill(out, raw.length, out.length, (byte) amount);
        return out;
    }

    private static byte[] unpad(byte[] padded) {
        if (padded.length == 0) {
            throw new IllegalArgumentException("企微密文为空");
        }
        int amount = padded[padded.length - 1] & 0xff;
        if (amount < 1 || amount > 32 || amount > padded.length) {
            throw new IllegalArgumentException("企微密文补位非法");
        }
        for (int i = padded.length - amount; i < padded.length; i++) {
            if ((padded[i] & 0xff) != amount) {
                throw new IllegalArgumentException("企微密文补位不一致");
            }
        }
        return Arrays.copyOf(padded, padded.length - amount);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** 测试与运维诊断用：当前是否具备解密能力（不打印密钥）。 */
    public String describe() {
        return configured() ? "configured" : "not-configured";
    }
}
