package steamstatus.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppDetailsApiResponseTest {

    private static final String RESPONSE_JSON = """
            {
              "570": {
                "success": true,
                "data": {
                  "type": "game",
                  "name": "Dota 2",
                  "steam_appid": 570
                }
              },
              "730": {
                "success": false
              }
            }
            """;

    @Test
    void readsChineseNameFromTopLevelAppIdMap() throws Exception {
        AppDetailsApiResponse response = new ObjectMapper()
                .readValue(RESPONSE_JSON, AppDetailsApiResponse.class);

        assertEquals("Dota 2", response.appName("570"));
        assertNull(response.appName("730"));
        assertNull(response.appName("999"));
    }
}
