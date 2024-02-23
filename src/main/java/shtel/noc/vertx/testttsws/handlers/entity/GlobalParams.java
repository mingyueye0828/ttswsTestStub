package shtel.noc.vertx.testttsws.handlers.entity;

import lombok.Data;
import lombok.Getter;

import java.util.concurrent.atomic.AtomicInteger;

@Data
public class GlobalParams {
//  线程安全的加减操作 AtomicInteger,主要有自增，自减去getAndDecrement()，自增getAndAdd()
  @Getter
  private static AtomicInteger i = new AtomicInteger(0);
}
