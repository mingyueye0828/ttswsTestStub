package shtel.noc.vertx.testttsws.verticles;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.URLUtil;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.BodyHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import shtel.noc.vertx.testttsws.handlers.entity.GlobalParams;
import shtel.noc.vertx.testttsws.handlers.entity.RequestSimParams;
import shtel.noc.vertx.testttsws.utils.ConfigurationKeys;
import shtel.noc.vertx.testttsws.utils.EventBusChannels;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


@Slf4j
public class MainVerticle extends AbstractVerticle {

    private static WebClient webClient;

    public static HttpClient WsClient;

    private int port;

    private static String savePath;

    private String logLevel;

    private static ArrayList<JsonObject> taskList;
    //所有共享
    private static RequestSimParams requestSimParams;

    private static ConcurrentHashMap<String, String> receiveInterval = new ConcurrentHashMap<>();
    //返回所需要的参数
    private static AtomicInteger returnCounter = new AtomicInteger(0);
    //原子类，计算返回的个数
    private static AtomicInteger CounterCouncurrency = new AtomicInteger(0);
    @Override
    public void start(Promise<Void> startPromise) {
        updateConfig(config().getJsonObject("adapter"));
        WsClient = vertx.createHttpClient();

//
//        WebClientOptions options = new WebClientOptions()
//                .setMaxPoolSize(50)
//                .setConnectTimeout(3000)
//                .setIdleTimeout(30)
//                .setKeepAlive(false);
//        webClient = WebClient.create(vertx, options);


        configureEventBus();

        Router router = Router.router(vertx);
//    Web 开发肯定需要处理POST请求和文件上传请求。默认Vert.x并不会处理http请求体，除非你显示设置了BodyHandler。
//    BodyHandler的作用是读取整个请求体，并将解析后请求体数据设置到RoutingContext。
//    BodyHandler的默认实现是BodyHandlerImpl
        router.route().handler(BodyHandler.create());
        router.route().consumes("application/json");
        router.route().produces("application/json");

        //建立外部访问的地址，指定客户端请求的方法，以post请求和下面地址访问时触发处理器，返回的是这里
        router.route(HttpMethod.POST, "/tts/v1/offline")
                .handler(r -> {
                    //r是传递给处理器的请求上下文对象,将请求体对象转化为json对象；
                    JsonObject body = r.getBodyAsJson();
                    //请求体对象进行内部映射
                    requestSimParams = new RequestSimParams(body);
                    //原子类进行加减
                    GlobalParams.getI().set(0);
                    log.info(new Date() + "  start to test online! concurrency " + requestSimParams.getConcurrency() + ", serviceUrl " + requestSimParams.getServiceUrl() + "   !!!!!!!!!!");

                    //这里返回ArrayList, 然后启动发送命令，并返回returnR,下面拆成两个函数
                    //进行测试，并获取返回结果,这个程序内部应该是要将数据传送到适配层，并且得到返回结果
                    taskList = simulator(requestSimParams);
                    log.info("start send {} text!", taskList.size());
                    AtomicInteger ix=new AtomicInteger(0);
                    vertx.setPeriodic(10,sp->{
                        //这段逻辑就是控制并发数，并将列表里的task发送完
                        if(CounterCouncurrency.getAndIncrement() < requestSimParams.getConcurrency()){
                            log.info(new Date() + "send text {}, wavCid {}", ix.get()+1, taskList.get(ix.get()));
                            log.info("now coucurrecny = {}, audio index = {}, taskList size = {}",CounterCouncurrency.get(),ix,taskList.size());
                            blockSender(taskList.get(ix.get()), requestSimParams.getServiceUrl());
                            ix.incrementAndGet();
                        }else{
                            CounterCouncurrency.decrementAndGet();
                        }
                        if(ix.get() >= taskList.size()){
                            vertx.cancelTimer(sp);
                            log.info("结束了3");
                        }
                    });
                    GlobalParams.getI().set(1);
                    r.response().end(String.format("start send text! required %d text, send %d text", requestSimParams.getConcurrency(), taskList.size()));
                });


        //监听端口
        vertx.createHttpServer().requestHandler(router).listen(this.port)
                .onSuccess(ar -> {
                    log.info("Http server create success!");
                    log.info("Deploy Stub success");
                    startPromise.complete();
                })
                .onFailure(err -> {
                    log.error("Create http server failed!", err);
                    startPromise.fail(err);
                });
    }

    /**
     * 模拟测试桩（一个音频一个测试桩任务，在这里进行是否需要循环）
     *
     * @param rsp 包含模拟器参数
     */
    private ArrayList<JsonObject> simulator(RequestSimParams rsp) {

        // 每个音频文件一个测试桩测试任务
        taskList = new ArrayList<>();
        try {
            //读取waves目录下所有音频，一个音频文件一路并发xxxdown.pcm为用户音频，生成任务列表及随路信息
            //对音频下的文件进行排序
            List<File> fileList = orderByName(rsp.getFilePath());
            if (fileList.isEmpty()) {
                log.error("Abort! can't find files in {}", rsp.getFilePath());
            }
            //读取列表下面的文件
            fileList.forEach(path -> {
                String dateString = DateUtil.format(new Date(), "yyyyMMddHHmmssSSS");
                //如果原子类处理小于并发或者是否转写文件夹下的全部目录,则执行下部(并发)
                if (GlobalParams.getI().addAndGet(1) <= rsp.getConcurrency() || rsp.isTransAll()) {
                    //处理文件下标，取文件的头部，将其按照“-”拆分,存在pathList中
                    String filePath = path.getName().split("\\.")[0];
                    log.info("!!!!!!!!!!!!!!!!!!!!!!!{}",filePath);

                    //taskList，用户任务条
                    taskList.add(
                        new JsonObject()
                            .put("uid", dateString+"-"+filePath)
                            .put("file", path)
                            .put("modelId", rsp.getModelId())
                            .put("serviceUrl", rsp.getServiceUrl())
                            .put("continues", rsp.isContinues())
                            .put("savePcm", rsp.isSavePcm())
                    );
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        return taskList;
    }

    //获取音频，发送给组件（这里就将语音流转换完成之后，然后发送给组件，发送完成之后就让返回地址接受数据）
    private void blockSender(JsonObject task, String serviceUrl) {
        //对音频进行处理，将其BASE64，之后加入data
        String textData = readTextFromFile(task.getString("file"));
        if (!textData.isEmpty()) {
            log.info("Success read text from file");
        } else {
            log.error("Failed to read text from file");
        }
        String uid = task.getString("uid");
        //这里是需要发送给manager的JSON参数,说话人分离，热词
        JsonObject data = new JsonObject()
                .put("uid", uid)
                .put("modelId", task.getString("modelId"))
                .put("text", textData)
                .put("params", new JsonObject().put("speaker", "bk611"));

        log.info("Start send uid {}, file {}", uid, task.getString("file"));

        // 组件的url
        URL url = URLUtil.url(URLUtil.normalize(serviceUrl));
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setHost(url.getHost())
                .setPort(url.getPort())
                .setURI(url.getPath());

        WsClient.webSocket(options, websocket ->{
           if(websocket.succeeded()){
               WebSocket ws = websocket.result();
               ws.writeFinalTextFrame(data.encode());

               ws.frameHandler(frame -> {
                   if (frame.isText()) {
                       JsonObject audioData = new JsonObject(frame.textData());
                       String sendUid = audioData.getString("uid");
                       if (audioData.getString("finish").equals("false")) {
                           log.info("Uid {} Received Start signal. Connection Beginning.",sendUid);
                           String startTime = DateUtil.format(new Date(), "yyyyMMddHHmmssSSS");
                           log.info("startTime is {}", startTime);
                           receiveInterval.put(sendUid, startTime);
                       }else {
                           log.info("Uid {} Received End signal. Connecting Finishing",audioData.getString("uid"));
                           String endTime1 = DateUtil.format(new Date(), "yyyyMMddHHmmssSSS");
                           log.info("endTime is {}", endTime1);
                           Long interval = Long.parseLong(endTime1)-Long.parseLong(receiveInterval.get(sendUid));
//                           LocalDateTime endTime = LocalDateTime.parse(endTime1, DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
//                           LocalDateTime startTime = LocalDateTime.parse(receiveInterval.get(sendUid), DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
//                           Duration interval = Duration.between(startTime, endTime);
                           log.info("Uid {}, 合成时间为：{} ms", sendUid, interval);
                           receiveInterval.remove("sendUid");
                           if(task.getBoolean("continues")){
                               for (JsonObject sendTask : taskList) {
                                   String taskUid= sendTask.getString("uid");
                                   String getUid = audioData.getString("uid");
                                   log.info("taskUid {}",taskUid);
                                   log.info("getUid {}",getUid);
                                   if (taskUid.equals(getUid)) {
                                       // 更新 wavCid 的循环次数
                                       log.info("send text again {} ", taskUid);
                                       //可以修改：Uid为文件名加时间戳
                                       // 获取当前时间并格式化为 "yyyyMMddHHmmssSSS"
//                                            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmssSSS");
//                                            String timestamp = sdf.format(new Date());
                                       String timestamp = DateUtil.format(new Date(), "yyyyMMddHHmmssSSS");
                                       sendTask.put("uid", timestamp + "-" + getUid.split("-")[1]);
                                       blockSender(sendTask, sendTask.getString("serviceUrl"));
                                       log.info("再次发送！！！！！！ uid {}", uid);
                                       break;
                                   }
                               }
                           }
                       }
                   } else if (frame.isBinary()) {
                       log.info("Uid is {}, Received binary data at: {}",uid, DateUtil.format(new Date(), "yyyyMMddHHmmssSSS"));
                       if(task.getBoolean("savePcm")){
                           saveBinaryData(uid, frame.binaryData());
                       }
                       // 直接输出
                       ws.writeBinaryMessage(frame.binaryData());
               }
           }); }
        });
    }

    public static void saveBinaryData(String uid, Buffer binaryData){
        try (FileOutputStream fos = new FileOutputStream(savePath + uid + ".pcm",true)) {
            fos.write(binaryData.getBytes());
            log.info("Binary data saved to audio_"+uid+ ".pcm");
        } catch (IOException e) {
            log.warn("Failed to save binary data: " + e.getMessage());
        }
    }

    /**
     * 对文件进行排序，因为传入的是音频文件夹，directory在前面，file在后面
     *
     * @param filePath
     * @return
     */
    public static List<File> orderByName(String filePath) {
        try {
            List<File> files = Arrays.asList(Objects.requireNonNull(new File(filePath).listFiles()));
            files.sort((o1, o2) -> {
                if (o1.isDirectory() && o2.isFile())
                    return -1;
                if (o1.isFile() && o2.isDirectory())
                    return 1;
                return o1.getName().compareTo(o2.getName());
            });
            return files;
        } catch (Exception e) {
            log.warn("get test file failed! ", e);
            return new ArrayList<>();
        }
    }

    /**
     * 读取音频文件
     */
    private static String readTextFromFile(String filePath) {
        try {
            Path path = Paths.get(filePath);
            List<String> lines = Files.readAllLines(path);
            StringBuilder sb = new StringBuilder();
            for (String line : lines) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }


    /**
     * 更新配置
     * @param configuration
     */
    private void updateConfig(JsonObject configuration) {
        this.port = configuration.getInteger(ConfigurationKeys.PORT.name());
        this.logLevel = configuration.getString(ConfigurationKeys.LOG_LEVEL.name());
        this.savePath = configuration.getString(ConfigurationKeys.SAVE_PATH.name());
        Configurator.setAllLevels("shtel.noc.vertx.testttsws", Level.valueOf(logLevel));
    }

    /**
     * 配置文件变更处理
     */
    private void configureEventBus() {
        vertx
                .eventBus()
                .<JsonObject>consumer(
                        EventBusChannels.CONFIGURATION_CHANGED.name(),
                        message -> {
                            try {
                                log.info("Configuration has changed, verticle {} is updating...",
                                        deploymentID());
                                configureHandlers(message.body());
                                log.info(
                                        "Configuration has changed, verticle {} has been updated...",
                                        deploymentID());
                            } catch (Exception e) {
                                log.error("Update config error!", e);
                            }
                        });
    }

    /***
     * 配置信息更新
     * @param configuration 更新后的配置信息
     */
    private void configureHandlers(JsonObject configuration) {
        //打印配置文件
        log.info(configuration.encodePrettily());
        updateConfig(configuration.getJsonObject("adapter"));
    }
}

