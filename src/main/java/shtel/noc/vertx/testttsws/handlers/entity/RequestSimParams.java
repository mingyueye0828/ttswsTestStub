package shtel.noc.vertx.testttsws.handlers.entity;

import io.vertx.core.json.JsonObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;


//这里是将请求参数与对应的内部参数进行映射（请求参数）

@Slf4j
@Data
public class RequestSimParams {
  private int concurrency;

  /**
   * the url of the asr module
   */
  private String serviceUrl;
  private String filePath;


  /**
   * need to convert all the audios in the dir?
   */
  private boolean transAll;

  private boolean savePcm;

  /**
   * is files are in pairs or not? if transAll == true, notPairs must = true
   */
  private String modelId;
  private boolean continues;


  public RequestSimParams(JsonObject requestJson){
    concurrency = requestJson.getInteger("concurrency");
    serviceUrl = requestJson.getString("serviceUrl");
    savePcm = requestJson.getBoolean("savePcm");
    filePath = requestJson.getString("filePath");
    modelId=requestJson.getString("modelId");
    if (modelId==null){
      modelId="159901";
    }
    transAll = requestJson.getBoolean("transAll");//是否转写该文件夹下全部音频
    continues = requestJson.getBoolean("continues");
  }
}




