package com.example.agent.context;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AccountContextBuilderTest {

    private Map<String, Object> account(long id, String name, String type, double balance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("type", type);
        m.put("balance", BigDecimal.valueOf(balance));
        m.put("userId", "default");
        return m;
    }

    @Test
    void emptyAccounts_emitsExplicitNotice() {
        String s = AccountContextBuilder.formatSummary(List.of());
        assertThat(s).contains("暂无账户");
    }

    @Test
    void nullAccounts_emitsExplicitNotice() {
        String s = AccountContextBuilder.formatSummary(null);
        assertThat(s).contains("暂无账户");
    }

    @Test
    void singleAccount_listsItFully() {
        String s = AccountContextBuilder.formatSummary(
                List.of(account(1, "默认现金账户", "CASH", 49224.43)));
        assertThat(s).contains("账户数: 1");
        assertThat(s).contains("总余额: ¥49224.43");
        assertThat(s).contains("ID=1");
        assertThat(s).contains("默认现金账户");
        assertThat(s).contains("¥49224.43");
        assertThat(s).doesNotContain("另有");
    }

    @Test
    void fiveAccounts_listsAllSortedByBalance() {
        List<Map<String, Object>> accs = new ArrayList<>();
        accs.add(account(1, "工资卡", "BANK", 8000));
        accs.add(account(2, "现金", "CASH", 12000));
        accs.add(account(3, "信用卡", "CREDIT", 500));
        accs.add(account(4, "存款", "BANK", 30000));
        accs.add(account(5, "余额宝", "INVEST", 5000));

        String s = AccountContextBuilder.formatSummary(accs);
        assertThat(s).contains("账户数: 5");
        assertThat(s).contains("总余额: ¥55500.00");
        // 按余额降序：30000 > 12000 > 8000 > 5000 > 500
        int idxBig = s.indexOf("存款");
        int idxSmall = s.indexOf("信用卡");
        assertThat(idxBig).isLessThan(idxSmall).isGreaterThan(-1);
        assertThat(s).doesNotContain("另有");
    }

    @Test
    void sixAccounts_listsTop5_andSummarizesRest() {
        List<Map<String, Object>> accs = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            accs.add(account(i, "账户" + i, "CASH", i * 1000.0));
        }
        String s = AccountContextBuilder.formatSummary(accs);
        assertThat(s).contains("账户数: 6");
        assertThat(s).contains("主要账户（按余额前 5）");
        assertThat(s).contains("另有 1 个账户余额合计 ¥1000.00");
        // 最大的 6000 应该出现在第一位（前 5 之列）
        assertThat(s).contains("账户6").contains("账户5").contains("账户4")
                .contains("账户3").contains("账户2");
        // 最小的"账户1"（id=1，余额 1000）落到"另有"段，所以字面 ID=1 不在前 5 ID 列表
        long idLines = s.lines().filter(l -> l.contains("ID=")).count();
        assertThat(idLines).isEqualTo(5);
        // 1000 余额被合并到"另有"段
        assertThat(s).contains("另有 1 个账户余额合计 ¥1000.00");
    }

    @Test
    void manyAccounts_thresholdNeverExceeded() {
        List<Map<String, Object>> accs = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            accs.add(account(i, "账户" + i, "CASH", 100.0));
        }
        String s = AccountContextBuilder.formatSummary(accs);
        assertThat(s).contains("账户数: 100");
        assertThat(s).contains("总余额: ¥10000.00");
        assertThat(s).contains("另有 95 个账户余额合计 ¥9500.00");
        // 数一下出现 "ID=" 应该恰好 5 次（前 5 个）
        long idCount = s.lines().filter(l -> l.contains("ID=")).count();
        assertThat(idCount).isEqualTo(5);
    }

    @Test
    void balanceParsedFromVariousNumberTypes() {
        Map<String, Object> a1 = new LinkedHashMap<>();
        a1.put("id", 1);
        a1.put("name", "a");
        a1.put("type", "CASH");
        a1.put("balance", 100.5);  // Double
        Map<String, Object> a2 = new LinkedHashMap<>();
        a2.put("id", 2);
        a2.put("name", "b");
        a2.put("type", "CASH");
        a2.put("balance", "200.5");  // String
        Map<String, Object> a3 = new LinkedHashMap<>();
        a3.put("id", 3);
        a3.put("name", "c");
        a3.put("type", "CASH");
        a3.put("balance", 300);  // Integer

        String s = AccountContextBuilder.formatSummary(List.of(a1, a2, a3));
        assertThat(s).contains("总余额: ¥601.00");
    }

    @Test
    void nullBalanceTreatedAsZero() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", 1);
        a.put("name", "broken");
        a.put("type", "CASH");
        a.put("balance", null);
        String s = AccountContextBuilder.formatSummary(List.of(a));
        assertThat(s).contains("总余额: ¥0.00");
    }
}
