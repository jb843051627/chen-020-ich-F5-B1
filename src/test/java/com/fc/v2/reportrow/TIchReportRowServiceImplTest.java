package com.fc.v2.reportrow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fc.v2.mapper.auto.TIchReportRowMapper;
import com.fc.v2.model.auto.TIchReportRow;
import com.fc.v2.service.impl.TIchReportRowServiceImpl;

/**
 * 履职情况报送核收行的规矩测试（Mapper 打桩，内存账本代库，不起 Spring）。
 *
 * <p>盯这几条：空批回 0、超上限整批拒收回 -1、批号一进门定死、逐行校验失败行留原始行号与原因、
 * 批内同码只收头一行、同一批号重发不重复入账（连点两下/重发前后两回拿到同一个数）、
 * 失败清单与落库同一口径且按原报行次升序。
 *
 * @author fuce
 * @date 2026-10-04
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class TIchReportRowServiceImplTest {

    @Mock
    private TIchReportRowMapper rowMapper;

    @InjectMocks
    private TIchReportRowServiceImpl service;

    private final List<TIchReportRow> store = new ArrayList<>();
    private final AtomicLong idSeq = new AtomicLong(100);

    @BeforeEach
    public void setUp() {
        when(rowMapper.insert(any())).thenAnswer(inv -> {
            TIchReportRow r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(idSeq.incrementAndGet());
            }
            store.add(r);
            return 1;
        });
        when(rowMapper.selectList(any())).thenAnswer(inv ->
                filter(new ArrayList<>(store), inv.getArgument(0)));
    }

    private TIchReportRow row(String code, String qty) {
        TIchReportRow r = new TIchReportRow();
        r.setItemCode(code);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        return r;
    }

    @Test
    public void 空批次回0一行不落() {
        assertEquals(0, service.submitBatch("B-EMPTY", null));
        assertEquals(0, service.submitBatch("B-EMPTY", new ArrayList<TIchReportRow>()));
        assertTrue(store.isEmpty());
    }

    @Test
    public void 超过单批上限整批拒收回负一() {
        List<TIchReportRow> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            rows.add(row("C" + i, "1"));
        }
        assertEquals(-1, service.submitBatch("B-OVER", rows));
        assertTrue(store.isEmpty());
    }

    @Test
    public void 批号以入参为准入参缺失才回明细上带的号两头都没有整批不收() {
        List<TIchReportRow> noNo = new ArrayList<>();
        noNo.add(row("C1", "1"));
        assertEquals(0, service.submitBatch("  ", noNo));
        assertTrue(store.isEmpty());

        TIchReportRow withNo = row("C1", "1");
        withNo.setBatchNo("B-ONROW");
        List<TIchReportRow> rows = new ArrayList<>();
        rows.add(withNo);
        assertEquals(1, service.submitBatch(null, rows));
        assertEquals("B-ONROW", store.get(0).getBatchNo());
    }

    @Test
    public void 逐行校验失败行留原始行号与原因合法行入库() {
        List<TIchReportRow> rows = new ArrayList<>();
        TIchReportRow ok = row("C-OK", "3");
        ok.setRowNo(10);
        rows.add(ok);
        TIchReportRow blankCode = row("  ", "1");
        blankCode.setRowNo(11);
        rows.add(blankCode);
        TIchReportRow noQty = row("C-NOQTY", null);
        noQty.setRowNo(12);
        rows.add(noQty);
        TIchReportRow negQty = row("C-NEG", "-2");
        negQty.setRowNo(13);
        rows.add(negQty);

        assertEquals(1, service.submitBatch("B-CHECK", rows));
        assertEquals(4, store.size());
        // 失败三行各带原因，原始行号原样留着
        assertEquals("明细码为空", store.get(1).getRemark());
        assertEquals(Integer.valueOf(11), store.get(1).getRowNo());
        assertEquals("数量缺失", store.get(2).getRemark());
        assertEquals("数量必须大于 0", store.get(3).getRemark());
        // 成功行状态已收下、失败行状态已退回
        assertEquals(1, store.get(0).getStatus().intValue());
        assertEquals(2, store.get(1).getStatus().intValue());
    }

    @Test
    public void 批内同码只收头一行重复行记失败明细() {
        List<TIchReportRow> rows = new ArrayList<>();
        rows.add(row("C-DUP", "1"));
        rows.add(row(" C-DUP ", "2"));
        assertEquals(1, service.submitBatch("B-DUP", rows));
        assertEquals(2, store.size());
        assertEquals(1, store.get(0).getStatus().intValue());
        assertEquals(2, store.get(1).getStatus().intValue());
        assertTrue(store.get(1).getRemark().startsWith("批内明细码重复"));
    }

    @Test
    public void 同一批号连点两回不重复入账前后拿到同一个数() {
        List<TIchReportRow> rows = new ArrayList<>();
        rows.add(row("C1", "1"));
        rows.add(row("C2", "2"));
        rows.add(row("  ", "3"));
        int first = service.submitBatch("B-REPLAY", rows);
        assertEquals(2, first);
        assertEquals(3, store.size());

        // 重发（连点两下/网络重试）：一行不添，回报库里已有的成功行数
        int again = service.submitBatch("B-REPLAY", rows);
        assertEquals(first, again);
        assertEquals(3, store.size());
    }

    @Test
    public void 失败清单只回该批失败行按原报行次升序() {
        List<TIchReportRow> rows = new ArrayList<>();
        TIchReportRow failLate = row("", "1");
        failLate.setRowNo(30);
        rows.add(failLate);
        TIchReportRow ok = row("C-OK", "1");
        ok.setRowNo(20);
        rows.add(ok);
        TIchReportRow failEarly = row("C-NEG", "0");
        failEarly.setRowNo(5);
        rows.add(failEarly);
        service.submitBatch("B-ERR", rows);

        List<TIchReportRow> errs = service.listErrors("B-ERR");
        assertEquals(2, errs.size());
        assertEquals(Integer.valueOf(5), errs.get(0).getRowNo());
        assertEquals(Integer.valueOf(30), errs.get(1).getRowNo());
        // 成功行从不进失败清单；空批号回空；别批的失败行不混进来
        assertTrue(service.listErrors("  ").isEmpty());
        assertTrue(service.listErrors("B-OTHER").isEmpty());
    }

    // ---------------------------------------------------------------- 通用件

    /**
     * 极简地读 QueryWrapper 里拼出的 WHERE 片段（column = value），
     * 只覆盖本服务用到的 eq——够把内存账本按同样条件筛一遍即可。
     */
    private <T> List<T> filter(List<T> all, Wrapper<T> wrapper) {
        List<T> out = new ArrayList<>();
        if (wrapper == null) {
            return all;
        }
        String sql = wrapper.getSqlSegment();
        final Map<String, Object> pairs = (wrapper instanceof com.baomidou.mybatisplus.core.conditions.AbstractWrapper)
                ? ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs()
                : null;
        Pattern p = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*#\\{[^}]*?\\.paramNameValuePairs\\.([A-Za-z0-9_]+)\\s*\\}");
        Matcher m = p.matcher(sql == null ? "" : sql);
        Map<String, Object> conds = new LinkedHashMap<>();
        while (m.find()) {
            conds.put(m.group(1), pairs == null ? null : pairs.get(m.group(2)));
        }
        for (T row : all) {
            boolean ok = true;
            for (Map.Entry<String, Object> cond : conds.entrySet()) {
                Object got = readColumn(row, cond.getKey());
                Object want = cond.getValue();
                boolean equal = (got == null && want == null)
                        || (got != null && want != null && String.valueOf(got).equals(String.valueOf(want)));
                if (!equal) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                out.add(row);
            }
        }
        return out;
    }

    /** 按下划线列名读实体字段（batch_no→batchNo 等） */
    private static Object readColumn(Object bean, String column) {
        String field = columnToField(column);
        Class<?> c = bean.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                return f.get(bean);
            } catch (NoSuchFieldException ignore) {
                c = c.getSuperclass();
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private static String columnToField(String column) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char ch : column.toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }
}
