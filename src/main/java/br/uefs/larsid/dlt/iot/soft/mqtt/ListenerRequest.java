package br.uefs.larsid.dlt.iot.soft.mqtt;

import br.uefs.larsid.dlt.iot.soft.entity.Sensor;
import br.uefs.larsid.dlt.iot.soft.services.Controller;
import br.uefs.larsid.dlt.iot.soft.utils.SortTopK;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttMessage;

public class ListenerRequest implements IMqttMessageListener {

  /*-------------------------Constantes---------------------------------------*/
  private static final String TOP_K_FOG = "TOP_K_HEALTH_FOG";
  private static final String TOP_K = "TOP_K_HEALTH";
  private static final String SENSORS_FOG = "SENSORS_FOG";
  private static final String SENSORS = "SENSORS";
  private static final String SENSORS_RES = "SENSORS_RES/";
  private static final String SENSORS_FOG_RES = "SENSORS_FOG_RES/";
  private static final String TOP_K_RES = "TOP_K_HEALTH_RES/";
  private static final String TOP_K_FOG_RES = "TOP_K_HEALTH_FOG_RES/";
  private static final String INVALID_TOP_K = "INVALID_TOP_K/";
  private static final String GET_SENSORS = "GET sensors";
  private static final int QOS = 1;
  /*--------------------------------------------------------------------------*/

  private boolean debugModeValue;
  private MQTTClient MQTTClientUp;
  private MQTTClient MQTTClientHost;
  private List<String> nodesUris;
  private Controller controllerImpl;

  /**
   * Método construtor.
   *
   * @param controllerImpl Controller - Controller que fará uso desse Listener.
   * @param MQTTClientUp   MQTTClient - Cliente MQTT do gateway superior.
   * @param MQTTClientHost   MQTTClient - Cliente MQTT do próprio gateway.
   * @param nodesUris   List<String> - Lista de URIs.
   * @param topics          String[] - Tópicos que serão assinados.
   * @param qos            int - Qualidade de serviço do tópico que será ouvido.
   * @param debugModeValue boolean - Modo para debugar o código.
   */
  public ListenerRequest(
    Controller controllerImpl,
    MQTTClient MQTTClientUp,
    MQTTClient MQTTClientHost,
    List<String> nodesUris,
    String[] topics,
    int qos,
    boolean debugModeValue
  ) {
    this.MQTTClientUp = MQTTClientUp;
    this.MQTTClientHost = MQTTClientHost;
    this.nodesUris = nodesUris;
    this.controllerImpl = controllerImpl;
    this.debugModeValue = debugModeValue;

    if (controllerImpl.hasNodes()) {
      for (String topic : topics) {
        this.MQTTClientUp.subscribe(qos, this, topic);
      }
    } else {
      for (String topic : topics) {
        this.MQTTClientHost.subscribe(qos, this, topic);
      }
    }
  }

  @Override
  public void messageArrived(String topic, MqttMessage message)
    throws Exception {
    /* params = [topic, id] */
    final String[] params = topic.split("/");
    final String mqttMessage = new String(message.getPayload());
    int k;

    switch (params[0]) {
      case TOP_K_FOG:
        k = Integer.valueOf(mqttMessage);
        if (k == 0) {
          printlnDebug("Top-K = 0");

          this.controllerImpl.sendEmptyTopK(params[1]);
        } else {
          if (controllerImpl.hasNodes()) {
            printlnDebug("==== Cloud gateway -> Fog gateway  ====");

            /* Criando uma nova chave, no mapa de requisições */
            this.controllerImpl.addResponse(params[1]);

            byte[] messageDown = message.getPayload();

            String topicDown = String.format("%s/%s", TOP_K, params[1]);

            this.publishToDown(topicDown, messageDown);

            Map<String, Integer> scoreMapEmpty = new LinkedHashMap<String, Integer>();

            this.controllerImpl.getTopKScores().put(params[1], scoreMapEmpty);

            /* Aguarda as respostas dos nós da camada inferior conectados a ele;
             * e publica para a camada superior o Top-K resultante.
             */
            this.controllerImpl.publishTopK(params[1], k);
          }
        }

        break;
      case TOP_K:
        k = Integer.valueOf(mqttMessage);
        printlnDebug("==== Fog gateway -> Bottom gateway  ====");
        printlnDebug("Calculating scores from devices...");

        Map<String, Integer> scores = new LinkedHashMap<String, Integer>();

        /*
         * Consumindo API Iot para resgatar os valores mais atualizados dos
         * dispositivos.
         */
        this.controllerImpl.loadConnectedDevices();

        /**
         * Se não houver nenhum dispositivo conectado.
         */
        if (this.controllerImpl.getDevices().isEmpty()) {
          printlnDebug("Sorry, there are no devices connected.");

          byte[] payload = scores.toString().getBytes();

          MQTTClientUp.publish(TOP_K_FOG_RES + params[1], payload, 1);
        } else {
          scores = this.controllerImpl.calculateScores();

          /*
           * Reordenando o mapa de Top-K (Ex: {device2=23, device1=14}) e
           * atribuindo-o à carga de mensagem do MQTT
           */
          Map<String, Integer> topK = SortTopK.sortTopK(
            scores,
            k,
            debugModeValue
          );

          if (k > scores.size()) {
            printlnDebug("Insufficient Top-K!");

            byte[] payload = String
              .format(
                "Can't possible calculate the Top-%s, sending the Top-%s!",
                k,
                scores.size()
              )
              .getBytes();

            MQTTClientUp.publish(INVALID_TOP_K + params[1], payload, 1);
          }

          printlnDebug("TOP_K => " + topK.toString());
          printlnDebug("=========================================");

          byte[] payload = topK.toString().getBytes();

          MQTTClientUp.publish(TOP_K_RES + params[1], payload, 1);
        }

        break;
      case SENSORS_FOG:
        switch (mqttMessage) {
          case GET_SENSORS:
            printlnDebug("==== Cloud gateway -> Fog gateway  ====");

            /**
             * Requisitando os dispositivos que estão conectados ao próprio nó.
             */
            this.controllerImpl.loadConnectedDevices();

            /**
             * Caso existam dispositivos conectados ao próprio nó.
             */
            if (this.controllerImpl.getDevices().size() > 0) {
              JsonObject json = new JsonObject();
              String deviceListJson = new Gson()
              .toJson(this.loadSensorsTypes());

              json.addProperty("sensors", deviceListJson);

              byte[] payload = json.toString().getBytes();

              MQTTClientUp.publish(SENSORS_FOG_RES, payload, 1);
            } else {
              this.controllerImpl.getSensorsTypesJSON()
                .addProperty("sensors", "[]");

              /* Criando uma nova chave, no mapa de requisições */
              this.controllerImpl.addResponse("getSensors");

              byte[] messageDown = message.getPayload();

              this.publishToDown(SENSORS + "/", messageDown);

              /* Aguarda as respostas dos nós da camada inferior conectados a
               * ele; e publica para a camada superior.
               */
              this.controllerImpl.publishSensorType();
            }

            break;
          default:
            String responseMessage = String.format(
              "\nOops! the request isn't recognized...\nTry one of the options below:\n- %s\n",
              GET_SENSORS
            );
            byte[] payload = responseMessage.getBytes();

            MQTTClientUp.publish(SENSORS_FOG_RES, payload, 1);

            break;
        }

        break;
      case SENSORS:
        byte[] payload;

        switch (mqttMessage) {
          case GET_SENSORS:
            printlnDebug("==== Fog gateway -> Bottom gateway  ====");

            /**
             * Requisitando os dispositivos que estão conectados ao próprio nó.
             */
            this.controllerImpl.loadConnectedDevices();

            JsonObject json = new JsonObject();
            String deviceListJson = new Gson().toJson(this.loadSensorsTypes());

            json.addProperty("sensors", deviceListJson);

            payload = json.toString().getBytes();

            MQTTClientUp.publish(SENSORS_RES, payload, 1);

            break;
          default:
            String responseMessage = String.format(
              "\nOops! the request isn't recognized...\nTry one of the options below:\n- %s\n",
              GET_SENSORS
            );
            payload = responseMessage.getBytes();

            MQTTClientUp.publish(SENSORS_RES, payload, 1);

            break;
        }
        break;
    }
  }

  /**
   * Publica a requisição para os nós filhos.
   *
   * @param topicDown String - Tópico.
   * @param messageDown byte[] - Mensagem que será enviada.
   */
  private void publishToDown(String topicDown, byte[] messageDown) {
    String user = this.MQTTClientUp.getUserName();
    String password = this.MQTTClientUp.getPassword();

    for (String nodeUri : this.nodesUris) {
      String uri[] = nodeUri.split(":");

      MQTTClient MQTTClientDown = new MQTTClient(
        this.debugModeValue,
        uri[0],
        uri[1],
        user,
        password
      );

      MQTTClientDown.connect();
      MQTTClientDown.publish(topicDown, messageDown, QOS);
      MQTTClientDown.disconnect();
    }
  }

  /**
   * Requisita os tipos de sensores de um dispositivo conectado.
   *
   * @return List<String>
   */
  private List<String> loadSensorsTypes() {
    List<String> sensorsList = new ArrayList<>();

    for (Sensor sensor : this.controllerImpl.getDevices().get(0).getSensors()) {
      sensorsList.add(sensor.getType());
    }

    return sensorsList;
  }

  private void printlnDebug(String str) {
    if (debugModeValue) {
      System.out.println(str);
    }
  }
}
