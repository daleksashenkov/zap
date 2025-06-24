package dev.da;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;

import java.net.http.HttpResponse;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class App {
    static final String CATALOG = "MMC202501";
    static final String SSD =
            "$*KwGkkIGx0M2n5uKk8fGojPzoyM_Ro6Wmpvby66Sm7s7m5OTSmYiT6Mjg4uDX2tP7gI2i1sWj1c2govnx6qDp5vT-1-uwpuPntN3RpdfHpNOa857Mp6CnpqCmsOPjvrStstOjpdGq-pvo17W8s_u1qrCIgO_Btbyz9fuyqO2Zid7f3KPbte0AAAAAJkJ2rQ==$";

    static final String CSV_DELIMITER = "; ";
    static final String PART_PATH_DELIMITER = " -> ";
    static final ObjectMapper om = new ObjectMapper();

    static final HttpClient hc = HttpClient.newHttpClient();

    static final int minDelayMs = 200;
    static final int maxDelayMs = 3000;

    static int getDelay() {
        return ThreadLocalRandom.current().nextInt(minDelayMs, maxDelayMs);
    }

    static HttpRequest get(URI uri) {
        return HttpRequest.newBuilder(uri).GET().build();
    }

    static URI buildUri(String path, List<Map.Entry<String, String>> params) throws URISyntaxException {
        return new URI((path.endsWith("?") ? path : path + '?') + params.stream()
                .map(e -> e.getKey() + '=' + e.getValue())
                .collect(Collectors.joining("&")));
    }

    static void parseDetail(StringBuilder sb, String path, JsonNode currentNode) throws URISyntaxException, JsonProcessingException {
        JsonNode idNode = currentNode.get("quickgroupid");
        if (idNode == null) {
            throw new RuntimeException("Error parsing leaf group " + currentNode);
        }
        String detailId = idNode.asText();

        URI quickDetail = buildUri("https://zap.ru/api/catalog/listQuickDetail",
                List.of(Map.entry("catalog", CATALOG),
                        Map.entry("ssd", SSD),
                        Map.entry("vehicleId", "0"),
                        Map.entry("groupId", detailId)));
        HttpRequest rq = get(quickDetail);
        HttpResponse<String> rs;

        try {
            Thread.sleep(getDelay());
            rs = hc.send(rq, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }

        JsonNode tree = om.readTree(rs.body());

        JsonNode categories = tree.get("categories");
        if (categories == null || !categories.isArray()) {
            System.err.println("Failed to parse quickDetail rs: " + tree);
            return;
        }

        categories.valueStream()
                .map(cat -> cat.get("units"))
                .flatMap(JsonNode::valueStream)
                .map(unit -> unit.get("details"))
                .flatMap(JsonNode::valueStream)
                .forEach(detail -> {
                    String name = detail.get("name").asText();
                    String oem = detail.get("oem").asText();
                    String row = oem + CSV_DELIMITER + path + PART_PATH_DELIMITER + name + '\n';
                    System.out.print(row);
                    sb.append(row);
                });


    }

    static void groups2csv(StringBuilder sb, String currentPath, JsonNode currentNode) throws URISyntaxException, JsonProcessingException {
        JsonNode childGroups = currentNode.get("childGroups");
        String prefix = currentPath.isEmpty() ? "" : currentPath + PART_PATH_DELIMITER;
        String path = prefix + currentNode.get("name").asText();
        if (childGroups == null || !childGroups.isArray() || childGroups.isEmpty()) {
            parseDetail(sb, path, currentNode);
        } else {
            for (JsonNode n : childGroups) {
                groups2csv(sb, path, n);
            }
        }
    }

    public static void main(String[] args) throws URISyntaxException, IOException, InterruptedException {
        URI rootQuickGroup = buildUri("https://zap.ru/api/catalog/listQuickGroup",
                List.of(Map.entry("catalog", CATALOG),
                        Map.entry("ssd", SSD),
                        Map.entry("vehicleId", "0")));

        HttpRequest rq = get(rootQuickGroup);
        HttpResponse<String> rs;

        rs = hc.send(rq, HttpResponse.BodyHandlers.ofString());

        JsonNode tree = om.readTree(rs.body());

        StringBuilder sb = new StringBuilder();
        for (JsonNode n : tree.get("childGroups")) {
            groups2csv(sb, "", n);
        }
        System.err.println("Printing result to std err:");
        System.err.print(sb);
    }
}
