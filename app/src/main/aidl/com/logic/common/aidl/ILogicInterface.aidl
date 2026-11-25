package com.logic.common.aidl;

import com.logic.common.aidl.ILogicCallback;

interface ILogicInterface {
    void registerLogicCallback(int domain, ILogicCallback callback);
    void unregisterLogicCallback(int domain, ILogicCallback callback);
    void setCmd(String cmd, String value, String extraValue);
    String getValue(String str, String str2);
}
