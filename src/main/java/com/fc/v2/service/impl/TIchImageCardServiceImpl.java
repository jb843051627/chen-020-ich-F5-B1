package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TIchImageCardMapper;
import com.fc.v2.mapper.auto.TIchProjectMapper;
import com.fc.v2.model.auto.TIchImageCard;
import com.fc.v2.model.auto.TIchProject;
import com.fc.v2.service.ITIchImageCardService;
import com.fc.v2.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 技艺影像卷立卷单Service业务层处理
 *
 * @author fuce
 * @date 2026-09-12
 */
@Service
public class TIchImageCardServiceImpl extends ServiceImpl<TIchImageCardMapper, TIchImageCard> implements ITIchImageCardService {

    /** 建卷进展：已冻住（冻住即锁档：卷面改不动、整张删不掉，与收口锁档同一规矩） */
    private static final int STATUS_FROZEN = 2;

    @Autowired
    private TIchProjectMapper ichProjectMapper;

    @Override
    public TIchImageCard selectTIchImageCardById(Long id) {
        return this.baseMapper.selectOne(new QueryWrapper<TIchImageCard>()
                .eq("id", id)
                .eq("del_flag", 0));
    }

    @Override
    public List<TIchImageCard> selectTIchImageCardList(Wrapper<TIchImageCard> queryWrapper) {
        // 覆盖范围与详情/落库同一口径：只看未删的；调用方传来的条件（卷号、进展等）一律保留，
        // 分页也交回调用方统一处理——旧写法另起空壳、硬塞 status=0 并自起一页，两处对不拢
        QueryWrapper<TIchImageCard> wrapper = queryWrapper instanceof QueryWrapper
                ? (QueryWrapper<TIchImageCard>) queryWrapper
                : new QueryWrapper<TIchImageCard>();
        wrapper.eq("del_flag", 0);
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    public int insertTIchImageCard(TIchImageCard record) {
        if (record == null) {
            return 0;
        }
        // 新建与编辑同一套校验：归属项目必须在册、未注销，卷号不得重
        TIchProject refArch = resolveActiveProject(record.getSiteId());
        if (refArch == null) {
            return 0;
        }
        if (duplicateBillNo(record.getBillNo(), record.getId())) {
            return 0;
        }
        record.setCreateBy(record.getBillNo());
        record.setSiteNo(refArch.getSiteNo());
        record.setDevRate(calcDevRate(record.getPlanQty(), record.getRealQty()));
        record.setDelFlag(0);
        return this.baseMapper.insert(record);
    }

    @Override
    public int updateTIchImageCard(TIchImageCard record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        // 编辑同样先过那一套校验，不许和新建两张规矩
        if (record.getSiteId() != null && resolveActiveProject(record.getSiteId()) == null) {
            return 0;
        }
        if (duplicateBillNo(record.getBillNo(), record.getId())) {
            return 0;
        }
        if (record.getSiteId() != null) {
            // 归属项目改了，顺着把项目代号也改成同一出处，避免 id 与 no 两张皮
            TIchProject refArch = resolveActiveProject(record.getSiteId());
            if (refArch != null) {
                record.setSiteNo(refArch.getSiteNo());
            }
        }
        // 应归/实归随表单带来时，用与新建同一套算法重折偏差率，不再一处算一处留旧值
        if (record.getPlanQty() != null && record.getRealQty() != null) {
            record.setDevRate(calcDevRate(record.getPlanQty(), record.getRealQty()));
        }
        record.setUpdateTime(new Date());
        // 冻住即锁档：库里这卷已冻住（status=2）便一个字都改不动，
        // 条件看的是库里现行值（NULL 视为未冻住），表单自带 status 也翻不开它
        return this.baseMapper.update(record, new UpdateWrapper<TIchImageCard>()
                .eq("id", record.getId())
                .eq("del_flag", 0)
                .and(w -> w.isNull("status").or().ne("status", STATUS_FROZEN)));
    }

    /** 取在册（未删、未注销）的归属项目；找不到回 null，新建/编辑共用 */
    private TIchProject resolveActiveProject(Integer siteId) {
        if (siteId == null) {
            return null;
        }
        TIchProject refArch = ichProjectMapper.selectOne(new QueryWrapper<TIchProject>()
                .eq("id", siteId).eq("del_flag", 0));
        if (refArch == null) {
            return null;
        }
        if (refArch.getStatus() != null && refArch.getStatus() == 1) {
            return null;
        }
        return refArch;
    }

    /** 卷号是否与别的未删立卷单重了；卷号空着不查重，新建/编辑共用 */
    private boolean duplicateBillNo(String billNo, Long selfId) {
        if (!StringUtils.isNotEmpty(billNo)) {
            return false;
        }
        QueryWrapper<TIchImageCard> qw = new QueryWrapper<TIchImageCard>()
                .eq("bill_no", billNo).eq("del_flag", 0);
        if (selfId != null) {
            qw.ne("id", selfId);
        }
        Integer dupCnt = this.baseMapper.selectCount(qw);
        return dupCnt != null && dupCnt > 0;
    }

    /**
     * 应归与实归两数折偏差率——新建/编辑唯一一套算法：
     * |实归-应归| / 分母（实归非 0 用实归，否则用应归）×100，保留 2 位；
     * 缺一数或应归为 0 没法折，回 0。
     */
    private BigDecimal calcDevRate(BigDecimal planQty, BigDecimal realQty) {
        if (planQty == null || planQty.signum() == 0 || realQty == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal divisor = realQty.signum() == 0 ? planQty : realQty;
        return realQty.subtract(planQty).abs()
                .multiply(new BigDecimal("100"))
                .divide(divisor, 2, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public int deleteTIchImageCardByIds(String ids) {
        Long[] idArr = ConvertUtil.toLongArray(ids);
        if (idArr == null || idArr.length == 0) {
            return 0;
        }
        // 软删：只把删除标记翻成 1，卷子仍留在册里可翻；与所有查询 del_flag=0 的口径对齐。
        // 冻住即锁档的卷子一张都不抹。
        TIchImageCard patch = new TIchImageCard();
        patch.setDelFlag(1);
        patch.setUpdateTime(new Date());
        return this.baseMapper.update(patch, new UpdateWrapper<TIchImageCard>()
                .in("id", Arrays.asList(idArr))
                .eq("del_flag", 0)
                .and(w -> w.isNull("status").or().ne("status", STATUS_FROZEN)));
    }

    @Override
    public int deleteTIchImageCardById(Long id) {
        if (id == null) {
            return 0;
        }
        // 软删：翻删除标记而非物理抹除，事后仍查得到这一卷；冻住卷删不掉
        TIchImageCard patch = new TIchImageCard();
        patch.setDelFlag(1);
        patch.setUpdateTime(new Date());
        return this.baseMapper.update(patch, new UpdateWrapper<TIchImageCard>()
                .eq("id", id)
                .eq("del_flag", 0)
                .and(w -> w.isNull("status").or().ne("status", STATUS_FROZEN)));
    }
}
