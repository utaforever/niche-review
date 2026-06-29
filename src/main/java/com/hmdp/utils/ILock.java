package com.hmdp.utils;

public interface ILock {
    /*
    * 尝试获取锁
    * @param timeoutSec 锁超时时间
    * @return true 获取锁成功
    * */
    boolean tryLock(Long timeoutSec);

    void unLock();
}
