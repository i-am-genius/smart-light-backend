package com.genius.smartlight.opsadmin;

import lombok.Data;

import java.util.List;

@Data
public class OpsAdminDeepSeekBalanceResp {

    private boolean configured;
    private boolean available;
    private List<BalanceInfo> balanceInfos;
    private String message;
    private String updateTime;

    @Data
    public static class BalanceInfo {
        private String currency;
        private String totalBalance;
        private String grantedBalance;
        private String toppedUpBalance;
    }
}
