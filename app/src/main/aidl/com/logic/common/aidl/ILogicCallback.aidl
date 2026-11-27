package com.logic.common.aidl;
interface ILogicCallback {
    void onEventChanged(String event, String value, String extraValue);
}
