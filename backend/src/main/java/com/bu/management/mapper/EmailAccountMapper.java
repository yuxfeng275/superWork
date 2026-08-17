package com.bu.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bu.management.entity.EmailAccount;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EmailAccountMapper extends BaseMapper<EmailAccount> {
  @Select("SELECT * FROM email_account WHERE enabled = 1")
  List<EmailAccount> selectEnabledAccounts();

  @Update(
      """
      UPDATE email_account
      SET lock_token = #{token}, lock_until = #{lockUntil},
          sync_status = 'RUNNING', sync_error = NULL, last_sync_started_at = #{now}
      WHERE id = #{id} AND owner_user_id = #{ownerUserId}
        AND (lock_until IS NULL OR lock_until < #{now})
      """)
  int acquireSyncLease(
      @Param("id") Long id,
      @Param("ownerUserId") Long ownerUserId,
      @Param("token") String token,
      @Param("now") LocalDateTime now,
      @Param("lockUntil") LocalDateTime lockUntil);
}
