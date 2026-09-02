package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.RevenueContractEntry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RevenueContractEntryMapper extends BaseMapper<RevenueContractEntry> {

    /**
     * 批量插入，明细表记录ID（detail_no 唯一键）重复时更新不新增 ——
     * 同一文件重复导入不产生重复行，同明细ID内容变化更新。
     */
    @Insert("""
            <script>
            INSERT INTO revenue_contract_entry
                (batch_id, contract_no, detail_no, contract_name, brand, customer, item_desc,
                 biz_line_raw, biz_line_id, project_id, receivable_amount, sale_month, delivery_date,
                 pending, created_by)
            VALUES
            <foreach collection="list" item="e" separator=",">
                (#{e.batchId}, #{e.contractNo}, #{e.detailNo}, #{e.contractName}, #{e.brand}, #{e.customer},
                 #{e.itemDesc}, #{e.bizLineRaw}, #{e.bizLineId}, #{e.projectId}, #{e.receivableAmount},
                 #{e.saleMonth}, #{e.deliveryDate}, #{e.pending}, #{e.createdBy})
            </foreach>
            ON DUPLICATE KEY UPDATE
                batch_id = VALUES(batch_id),
                contract_no = VALUES(contract_no),
                contract_name = VALUES(contract_name),
                brand = VALUES(brand),
                customer = VALUES(customer),
                item_desc = VALUES(item_desc),
                biz_line_raw = VALUES(biz_line_raw),
                biz_line_id = VALUES(biz_line_id),
                project_id = VALUES(project_id),
                receivable_amount = VALUES(receivable_amount),
                sale_month = VALUES(sale_month),
                delivery_date = VALUES(delivery_date),
                pending = VALUES(pending),
                created_by = VALUES(created_by)
            </script>
            """)
    int upsertBatch(@Param("list") List<RevenueContractEntry> entries);
}
