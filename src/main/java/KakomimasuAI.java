import java.io.*;
import java.net.*;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class KakomimasuAI {

    public void start() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var objectMapper = new ObjectMapper();
            var textBodyHandler = HttpResponse.BodyHandlers.ofString();
            // ゲームを作成
            var baseUrl = "https://api.kakomimasu.com";
            var startURL = URI.create(baseUrl + "/v1/matches/ai/players");
            var startPayload = Map.of("guestName", "☕Javaくん", "aiName", "a1", "boardName", "A-1", "nAgent", 1);
            var startHeaders = new String[] {"Content-Type", "application/json"};
            var startBody = HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(startPayload));
            var startRequest = HttpRequest.newBuilder().uri(startURL).headers(startHeaders).POST(startBody).build();
            var startResponse = client.send(startRequest, textBodyHandler).body();
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
            var start = game.get("startedAtUnixTime").asLong() * 1000;
            var opsec = game.get("operationSec").asInt() * 1000;
            var trsec = game.get("transitionSec").asInt() * 1000;
            // 1ターン目を待つ
            var startSleepTime = Math.max(start - System.currentTimeMillis(), 0);
			System.out.println("startSleepTime = " + startSleepTime);
            Thread.sleep(startSleepTime);
            // メインループ
            while (true) {
                while (true) {
                    var turnURL = URI.create(baseUrl + "/v1/matches/" + gameId);
                    var turnRequest = HttpRequest.newBuilder().uri(turnURL).GET().build();
                    var turnResponse = client.send(turnRequest, textBodyHandler);
					var turnResponseBody = turnResponse.body();
					var turnStatusCode = turnResponse.statusCode();
                    if (turnStatusCode == 200) {
                        game = objectMapper.readTree(turnResponseBody);
                        break;
                    }
                    Thread.sleep(100);
                }
                var status = game.get("status").asText();
                if (status.equals("ended")) {
                    break;
                }
                var agent = game.get("players").get(0).get("agents").get(0);

                // 盤面をStateに変換する
                var width = game.get("field").get("width").asInt();
                var height = game.get("field").get("height").asInt();
                var endTurn = game.get("totalTurn").asInt();
                var state = new State(width, height, endTurn);
                state.turn = game.get("turn").asInt();
                state.character.x = agent.get("x").asInt();
                state.character.y = agent.get("y").asInt();
                var points = game.get("field").get("points");
                var tiles = game.get("field").get("tiles");
                for (var y = 0; y < state.h; y++) {
                    for (var x = 0; x < state.w; x++) {
                        var idx = state.w * y + x;
                        var tile = tiles.get(idx);
                        var type = tile.get("type").asInt();
                        var player = tile.get("player").asInt();
                        var point = points.get(idx).asInt();
                        var newPoint = 0;
                        if (!(type == 1 && player == 0)) {
                            newPoint = point;
                        }
                        state.points[y][x] = newPoint;
                    }
                }
                state.print();

                // BeamSearch
                var bestdx = 0;
                var bestdy = 0;
                
                var beamStartTime = System.currentTimeMillis();
                if (state.character.x != -1) {
                    var beamWidth = 10000;
                    var beamDepth = 30;
                    var nowBeam = new PriorityQueue<State>(Comparator.comparingLong(s -> -s.evaluatedScore));
                    State bestState = null;
                    nowBeam.add(state);
                    for (var t = 0; t < beamDepth; t++) {
                        // System.out.println("depth: " + t);
                        var nextBeam = new PriorityQueue<State>(Comparator.comparingLong(s -> -s.evaluatedScore));
                        for (var i = 0; i < beamWidth; i++) {
                            // System.out.print(i + " ");
                            if (nowBeam.isEmpty()) {
                                break;
                            }
                            var nowState = nowBeam.poll();
                            var legalActions = nowState.legalActions();
                            for (var action : legalActions) {
                                var nextState = nowState.clone();
                                nextState.advance(action);
                                nextState.evaluateScore();
                                if (t == 0) {
                                    nextState.firstAction = action;
                                }
                                nextBeam.add(nextState);
                            }
                        }
                        nowBeam = new PriorityQueue<>(nextBeam);
                        bestState = nowBeam.peek();
                        if (bestState.isDone()) {
                            break;
                        }
                    }
                    bestdx = State.dx[bestState.firstAction];
                    bestdy = State.dy[bestState.firstAction];
                }
                var beamEndTime = System.currentTimeMillis();
                System.out.println("beamTime = " + (beamEndTime - beamStartTime));

                // 忍者の動きの送信
                var acitonPayload = new HashMap<>();
                Map<String, Object> action1 = null;
				if (state.character.x == -1) {
					action1 = Map.of("agentId", 0, "type", "PUT", "x", 4, "y", 4);
				} else {
					var nx = state.character.x + bestdx;
					var ny = state.character.y + bestdy;
					action1 = Map.of("agentId", 0, "type", "MOVE", "x", nx, "y", ny);
				}
				if (action1 != null) {
					var actions = List.of(action1);
					acitonPayload.put("actions", actions);
					var actionURL = URI.create(baseUrl + "/v1/matches/" + gameId + "/actions");
					var actionBody = HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(acitonPayload));
					var actionHeaders = new String[] {"Content-Type", "application/json", "Authorization", pic};
					var actionRequest = HttpRequest.newBuilder().uri(actionURL).method("PATCH", actionBody).headers(actionHeaders).build();
					client.send(actionRequest, textBodyHandler);
				}
                var sleepTime = Math.max(start + (long) (opsec + trsec) * state.turn - System.currentTimeMillis(), 0);
                Thread.sleep(sleepTime);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        new KakomimasuAI().start();
    }
}

class Coord {
    int y, x;

    Coord(int y, int x) {
        this.y = y;
        this.x = x;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ")";
    }
}

class State implements Cloneable {
    static int[] dx = {1, -1, 0, 0};
    static int[] dy = {0, 0, 1, -1};
    final int h;
    final int w;
    final int END_TURN;

    int[][] points;
    int turn = 0;
    Coord character = new Coord(0, 0);
    int gameScore = 0;
    long evaluatedScore = 0;
    int firstAction = -1;

    State(int h, int w, int endTurn) {
        this.h = h;
        this.w = w;
        this.END_TURN = endTurn;
        this.points = new int[h][w];
    }

    boolean isDone() {
        return this.turn == END_TURN;
    }

    void evaluateScore() {
        this.evaluatedScore = this.gameScore;
    }

    void advance(int action) {
        this.character.x += dx[action];
        this.character.y += dy[action];
        var point = this.points[this.character.y][this.character.x];
        if (point > 0) {
            this.gameScore += point;
            this.points[this.character.y][this.character.x] = 0;
        }
        this.turn++;
    }

    List<Integer> legalActions() {
        var actions = new ArrayList<Integer>();
        for (var action = 0; action < 4; action++) {
            var ty = this.character.y + dy[action];
            var tx = this.character.x + dx[action];
            if (ty >= 0 && ty < h && tx >= 0 && tx < w) {
                actions.add(action);
            }
        }
        return actions;
    }

    @Override
    protected State clone() {
        var nextState = new State(this.w, this.h, this.END_TURN);
        nextState.character = new Coord(this.character.y, this.character.x);
        for (var y = 0; y < this.h; y++) {
            for (var x = 0; x < this.w; x++) {
                nextState.points[y][x] = this.points[y][x];
            }
        }
        nextState.turn = this.turn;
        nextState.gameScore = this.gameScore;
        nextState.firstAction = this.firstAction;
        nextState.evaluatedScore = this.evaluatedScore;
        return nextState;
    }

    void print() {
        System.out.println("turn: " + turn);
        var ss = new StringBuilder();
        for (var y = 0; y < h; y++) {
            for (var x = 0; x < w; x++) {
                ss.append(String.format("%3d", points[y][x]));
            }
            ss.append("\n");
        }
        System.out.println(ss);
        System.out.println();
    }
}
