package shtel.noc.vertx.testttsws.utils;

/***
 * 定义eventBus channels
 * @author zhuyunfeng
 */
public enum EventBusChannels {
    // 配置变更
    CONFIGURATION_CHANGED,
    // redis
    SET_REDIS_OPTIONS,
    GET_DISTRIBUTED_LOCK,
    RELEASE_DISTRIBUTED_LOCK,
    REDIS_CONNECT,

    //周期任务
    PERIOD_JOB_RUN,
}
