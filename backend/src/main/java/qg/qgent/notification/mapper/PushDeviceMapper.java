package qg.qgent.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.notification.entity.PushDeviceEntity;

import java.util.List;

/**
 * 移动端推送设备数据访问。
 */
@Mapper
public interface PushDeviceMapper extends BaseMapper<PushDeviceEntity> {
    @Select(
        "SELECT * FROM push_devices WHERE user_id=#{userId} AND installation_id=#{installationId} LIMIT 1")
    PushDeviceEntity selectInstallation(
        @Param("userId") Long userId, @Param("installationId") String installationId);

    @Select("SELECT * FROM push_devices WHERE user_id=#{userId} AND active=1 ORDER BY id")
    List<PushDeviceEntity> selectActiveByUser(@Param("userId") Long userId);
}
