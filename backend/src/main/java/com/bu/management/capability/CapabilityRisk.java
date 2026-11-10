package com.bu.management.capability;

/**
 * 能力风险等级：只区分「会不会改世界」。
 *
 * <p>刻意只留两档，不做细粒度分级——分级越细越没人认真标，
 * 而 WRITE 是唯一需要过 Jev 写操作门禁的一档，标错代价最高，所以保持最简。
 *
 * @author BU Team
 * @since 2026-11-20
 */
public enum CapabilityRisk {

    /** 只读：查询 / 检索 / 分析，可随意重试。 */
    READ,

    /** 写操作：发信、建待办、落库，必须经过 Jev 门禁。 */
    WRITE
}
