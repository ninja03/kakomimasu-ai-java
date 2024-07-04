import java.io.*;
import java.net.*;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.lang.Math;

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
            var start = game.get("startedAtUnixTime").asLong();
            var opsec = game.get("operationSec").asInt();
            var trsec = game.get("transitionSec").asInt();
            // 1ターン目を待つ
            var startSleepTime = Math.max(start * 1000 - System.currentTimeMillis(), 0);
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

                var state = new State(width, height);
                state.END_TURN = game.get("totalTurn").asInt();
                state.turn = game.get("turn").asInt();
                state.character.x = agent.get("x").asInt();
                state.character.y = agent.get("y").asInt();
                var points = game.get("field").get("points");
                var tiles = game.get("field").get("tiles");
                for (var y = 0; y < state.H; y++) {
                    for (var x = 0; x < state.W; x++) {
                        var idx = y + state.W * x;
                        var tile = tiles.get(idx);
                        state.points[y][x] = points.get(idx).asInt();
                        /*
                        var point = objectMapper.createObjectNode()
                                .put("point", points.get(idx).asInt())
                                .put("type", tile.get("type").asText())
                                .put("player", tile.get("player").asInt());
                        board[y][x] = point;
                        */    
                    }
                }
                System.out.println("turn: " + state.turn);
                var ss = new StringBuilder();
                for (var y = 0; y < state.H; y++) {
                    for (var x = 0; x < state.W; x++) {
                        ss.append(String.format("%3d", state.points[y][x]));
                    }
                    ss.append("\n");
                }
                // System.out.print(ss);

                // BeamSearch
                if (state.character.x != -1) {
                    var beamDepth = 2;
                    var beamWidth = 2;
                    var nowBeam = new PriorityQueue<State>(Comparator.comparingLong(s -> -s.evaluatedScore));
                    State bestState = null;
                    nowBeam.add(state);
                    System.out.println("nowBeam.size = " + nowBeam.size());
                    for (var t = 0; t < beamDepth; t++) {
                        var nextBeam = new PriorityQueue<State>(Comparator.comparingLong(s -> -s.evaluatedScore));
                        for (var i = 0; i < beamWidth; i++) {
                            if (nowBeam.isEmpty()) {
                                break;
                            }
                            var nowState = nowBeam.poll();
                            System.out.println("nowState.character = " + nowState.character.x + " " + nowState.character.y);
                            var legalActions = nowState.legalActions();
                            System.out.println("legalActions.size() = " + legalActions.size());
                            for (var action : legalActions) {
                                var nextState = state.clone();
                                nextState.advance(action);
                                nextState.evaluateScore();
                                if (t == 0) {
                                    nextState.firstAction = action;
                                }
                                nextBeam.add(nextState);
                            }
                        }
                        nowBeam = new PriorityQueue<>(nextBeam);
                        System.out.println("nextBeam.size = " + nextBeam.size());
                        bestState = nowBeam.peek();
                        System.out.println("bestState = " + bestState);
                        if (bestState.isDone()) {
                            break;
                        }
                    }
                    System.out.println("bestState.firstAction = " + bestState.firstAction);
                }

                // 忍者の動きの送信
                var acitonPayload = new HashMap<>();
                Map<String, Object> action1 = null;
				if (state.character.x == -1) {
					action1 = Map.of("agentId", 0, "type", "PUT", "x", 4, "y", 4);
				} else {
                    var dx = State.dx[best]
					var nx = Math.random() > 0.5 ? state.character.x-1 : state.character.x+1;
					var ny = Math.random() > 0.5 ? state.character.y-1 : state.character.y+1;
					action1 = Map.of("agentId", 0, "type", "MOVE", "x", nx, "y", ny);
				}
				System.out.println(action1);
				if (action1 != null) {
					var actions = List.of(action1);
					acitonPayload.put("actions", actions);
					var actionURL = URI.create(baseUrl + "/v1/matches/" + gameId + "/actions");
					var actionBody = HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(acitonPayload));
					var actionHeaders = new String[] {"Content-Type", "application/json", "Authorization", pic};
					var actionRequest = HttpRequest.newBuilder().uri(actionURL).method("PATCH", actionBody).headers(actionHeaders).build();
					client.send(actionRequest, textBodyHandler);
				}
                var sleepTime = Math.max(start * 1000 + (long) (opsec * 1000 + trsec * 1000) * state.turn - System.currentTimeMillis(), 0);
                Thread.sleep(sleepTime);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        new KakomimasuAI().start();
    }
}

class Coord {
    int x, y;

    Coord(int x, int y) {
        this.x = x;
        this.y = y;
    }
}

class State implements Cloneable {
    static int[] dx = {1, -1, 0, 0};
    static int[] dy = {0, 0, 1, -1};
    int H = 3;
    int W = 4;
    int END_TURN = 4;

    int[][] points;
    int turn = 0;
    Coord character = new Coord(0, 0);
    int gameScore = 0;
    long evaluatedScore = 0;
    int firstAction = -1;

    State(int w, int h) {
        this.W = w;
        this.H = h;
        this.points = new int[H][W];
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
            if (ty >= 0 && ty < H && tx >= 0 && tx < W) {
                actions.add(action);
            }
        }
        return actions;
    }

    @Override
    protected State clone() {
        var nextState = new State(this.W, this.H);
        nextState.character = new Coord(this.character.y, this.character.x);
        nextState.points = Arrays.stream(this.points).map(int[]::clone).toArray(int[][]::new);
        nextState.turn = this.turn;
        nextState.gameScore = this.gameScore;
        nextState.firstAction = this.firstAction;
        return nextState;
    }
}
