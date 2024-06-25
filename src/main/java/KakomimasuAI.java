import java.io.*;
import java.net.*;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class KakomimasuAI {

    public static void main(String[] args) throws IOException, InterruptedException {
        try (var client = HttpClient.newHttpClient()) {
            var objectMapper = new ObjectMapper();
            var textBodyHandler = HttpResponse.BodyHandlers.ofString();
            // ゲームを作成
            var baseUrl = "https://api.kakomimasu.com";
            var startURL = URI.create(baseUrl + "/v1/matches/ai/players");
            var startPayload = Map.of("guestName", "java", "aiName", "a1", "boardName", "A-1", "nAgent", 1);
            var startHeaders = new String[] {"Content-Type", "application/json"};
            var startBody = HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(startPayload));
            var startRequest = HttpRequest.newBuilder().uri(startURL).headers(startHeaders).POST(startBody).build();
            var startResponse = client.send(startRequest, HttpResponse.BodyHandlers.ofString()).body();
            var game = objectMapper.readTree(startResponse);
            var gameId = game.get("gameId").asText();
            var pic = game.get("pic").asText();
            // 赤忍者を待つ
            while (true) {
                var waitStartURL = URI.create(baseUrl + "/v1/matches/" + gameId);
                var waitStartRequest = HttpRequest.newBuilder().uri(waitStartURL).GET().build();
                var waitStartResponse = client.send(waitStartRequest, textBodyHandler).body();
                game = objectMapper.readTree(waitStartResponse);
                if (game.get("startedAtUnixTime").asLong() != 0) {
                    break;
                }
                Thread.sleep(100);
            }
            // 盤面を表示
            var start = game.get("startedAtUnixTime").asLong();
            var opsec = game.get("operationSec").asInt();
            var trsec = game.get("transitionSec").asInt();
            var width = game.get("field").get("width").asInt();
            var height = game.get("field").get("height").asInt();
            var board = new JsonNode[height][width];
            var points = game.get("field").get("points");
            var tiles = game.get("field").get("tiles");
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var idx = y + width * x;
                    var tile = tiles.get(idx);
                    var point = objectMapper.createObjectNode()
                            .put("point", points.get(idx).asInt())
                            .put("type", tile.get("type").asText())
                            .put("player", tile.get("player").asInt());
                    board[y][x] = point;
                }
            }
            var ss = new StringBuilder();
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    ss.append(String.format("%3d", board[y][x].get("point").asInt()));
                }
                ss.append("\n");
            }
            System.out.print(ss);
            // 1ターン目を待つ
            var starSleepTime = Math.max(start - System.currentTimeMillis() / 1000, 0);
            Thread.sleep(starSleepTime);
            // メインループ
            while (true) {
                while (true) {
                    var turnURL = URI.create(baseUrl + "/v1/matches/" + gameId);
                    var turnRequest = HttpRequest.newBuilder().uri(turnURL).GET().build();
                    var turnResponse = client.send(turnRequest, textBodyHandler).body();
                    if (!turnResponse.isEmpty()) {
                        game = objectMapper.readTree(turnResponse);
                        break;
                    }
                    Thread.sleep(100);
                }
                var status = game.get("status").asText();
                var turn = game.get("turn").asInt();
                if (status.equals("ended")) {
                    break;
                }
                System.out.println(turn);
                // 忍者の動きの送信
                var acitonPayload = new HashMap<>();
                var action1 = Map.of("agentId", 0, "type", "PUT", "x", 4, "y", 4);
                var actions = List.of(action1);
                acitonPayload.put("actions", actions);
                var actionURL = URI.create(baseUrl + "/v1/matches/" + gameId + "/actions");
                var actionBody = HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(acitonPayload));
                var actionHeaders = new String[] {"Content-Type", "application/json", "Authorization", pic};
                var actionRequest = HttpRequest.newBuilder().uri(actionURL).method("PATCH", actionBody).headers(actionHeaders).build();
                client.send(actionRequest, textBodyHandler);
                var sleepTime = Math.max(start + (long) (opsec + trsec) * turn - System.currentTimeMillis() / 1000, 0);
                Thread.sleep(sleepTime);
            }
        }
    }
}
