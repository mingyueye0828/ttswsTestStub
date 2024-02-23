package shtel.noc.vertx.testttsws;

import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.*;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import shtel.noc.vertx.testttsws.handlers.common.ExceptionHandler;
import shtel.noc.vertx.testttsws.utils.EventBusChannels;
import shtel.noc.vertx.testttsws.verticles.MainVerticle;

/**
 * @author JWZ
 * @version 1.0
 * @date 2024/2/22
 * @annotation tts ws测试桩 每个包的时间，以及发送到最终音频合成时间
 */
@Slf4j
public class Application {

    private static Vertx vertx;

    public static void main(String[] args) {
        // 日志接口设置
        System.setProperty("vertx.log-delegate-factory-class-name", "io.vertx.core.logging.SLF4JLogDelegateFactory");

        // vert部署参数设置
        VertxOptions vertxOptions = new VertxOptions()
                // 工作线程池大小，默认20
                .setWorkerPoolSize(50);
        vertx = Vertx.vertx(vertxOptions);

        // 读取配置(ConfigurationKeys：配置文件的Key,xxx.properties:value)
        ConfigRetrieverOptions configRetrieverOptions = getConfigRetrieverOptions();
        ConfigRetriever configRetriever = ConfigRetriever.create(vertx, configRetrieverOptions);
        configRetriever.getConfig(
                ar -> {
                    // cpu核数
                    int instances = Runtime.getRuntime().availableProcessors();
                    try {
                        int inputInstanceCount = ar.result().getInteger("INSTANCE_COUNT");
                        if (inputInstanceCount > 0 && inputInstanceCount < instances) {
                            instances = inputInstanceCount;
                        }
                    }catch (Exception e){
                        log.warn("instance number not set! will use all {} cores!",instances);
                    }

                    // 根据读取的配置文件和核数部署vertx
                    DeploymentOptions deploymentOptions =
                            new DeploymentOptions().setInstances(instances).setConfig(ar.result());
                    vertx.exceptionHandler(new ExceptionHandler());
                    deployVertx(deploymentOptions);
                });

        // 监听配置文件更改（5秒）
        configRetriever.listen(
                change ->
                {
                    JsonObject updatedConfiguration = change.getNewConfiguration();
                    vertx.eventBus().publish(EventBusChannels.CONFIGURATION_CHANGED.name(), updatedConfiguration);
                });
    }

    /***
     * 部署vertx,main vertical,确认应用部署结果。
     * @param deploymentOptions deploy配置参数
     */
    private static void deployVertx(DeploymentOptions deploymentOptions) {

        deployVerticle(MainVerticle.class, deploymentOptions)
                .onFailure(res -> log.error("Deploy Stub for ASR Online failed!", res))
                .onSuccess(res -> {
                    log.info("Stub for ASR Online has been deployed!");
                });

    }

    private static Future<Void> deployVerticle(Class<? extends Verticle> verticleClass, DeploymentOptions option) {
        return Future.future(result -> vertx.deployVerticle(verticleClass, option, r -> {
            if (r.succeeded()) {
                result.complete();
            } else {
                result.fail(r.cause());
            }
        }));
    }


    private static ConfigRetrieverOptions getConfigRetrieverOptions() {
        // 默认配置文件
        JsonObject classpathFileConfiguration = new JsonObject()
                .put("path", "dev.properties")
                .put("hierarchical", true);

        ConfigStoreOptions classpathFile = new ConfigStoreOptions()
                .setType("file")
                .setFormat("properties")
                .setConfig(classpathFileConfiguration);

        // 外部配置文件（K8s下使用configMap配置,测试可配置dev或local)
        JsonObject envFileConfiguration = new JsonObject()
                .put("path", "/opt/pro.properties")
                .put("hierarchical", true);

        ConfigStoreOptions envFile = new ConfigStoreOptions()
                .setType("file")
                .setFormat("properties")
                .setConfig(envFileConfiguration)
                .setOptional(true);

        // 默认优先级envFile>classpathFile
        return new ConfigRetrieverOptions()
                .addStore(classpathFile)
                .addStore(envFile)
                .setScanPeriod(5000);
    }

}
