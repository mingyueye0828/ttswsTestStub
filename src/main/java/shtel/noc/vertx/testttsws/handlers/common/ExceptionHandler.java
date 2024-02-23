package shtel.noc.vertx.testttsws.handlers.common;

import io.vertx.core.Handler;
import lombok.extern.slf4j.Slf4j;

/**
 * 其他异常处理
 * 各类非捕获异常打印
 *
 * @author zhuyunfeng
 */
@Slf4j
public class ExceptionHandler implements Handler<Throwable> {

    @Override
    public void handle(Throwable event) {
        // 暂时打印错误信息
        log.error("Exception: ", event);

    }
}
