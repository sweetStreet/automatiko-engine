package org.automatiko.funq;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;

import org.automatiko.funq.MockEventSource.EventData;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

@QuarkusTest
public class GreetingsTest {

    @Inject
    MockEventSource eventSource;

    // @formatter:off
    @Test
    public void testStartFunctionEndpoint() {
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .header("ce-id", UUID.randomUUID().toString())
            .header("ce-type", "org.acme.travels.greetings")
            .header("ce-source", "test")
            .body("{\"name\" : \"john\"}")
        .when()
            .post("/")
        .then()
            .statusCode(204);
        
        List<EventData> events = eventSource.events();
        assertEquals(2, events.size());
        
        EventData data = events.get(0);
        assertEquals("org.acme.travels.greetings", data.source);
        assertEquals("org.acme.travels.greetings.updatemessage", data.type);
        
        data = events.get(1);
        assertEquals("org.acme.travels.greetings", data.source);
        assertEquals("org.acme.travels.greetings.greeting", data.type);
        
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .header("ce-id", UUID.randomUUID().toString())
            .header("ce-type", "org.acme.travels.greetings.updatemessage")
            .header("ce-source", "test")
            .body("{\"name\" : \"john\"}")
        .when()
            .post("/")
        .then()
            .statusCode(204);
        
        events = eventSource.events();
        assertEquals(1, events.size());
        
        data = events.get(0);
        assertEquals("org.acme.travels.greetings.updatemessage", data.source);
        assertEquals("org.acme.travels.greetings.end", data.type);       
    }
    
    @Test
    public void testStartFunctionEndpointOtherPath() {
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .header("ce-id", UUID.randomUUID().toString())
            .header("ce-type", "org.acme.travels.greetings")
            .header("ce-source", "test")
            .body("{\"name\" : \"mary\"}")
        .when()
            .post("/")
        .then()
            .statusCode(204);
        List<EventData> events = eventSource.events();
        assertEquals(2, events.size());
        
        EventData data = events.get(0);
        assertEquals("org.acme.travels.greetings", data.source);
        assertEquals("org.acme.travels.greetings.spanishname", data.type);
        
        data = events.get(1);
        assertEquals("org.acme.travels.greetings", data.source);
        assertEquals("org.acme.travels.greetings.greeting", data.type);
        
        given()
            .contentType(ContentType.JSON)
            .accept(ContentType.JSON)
            .header("ce-id", UUID.randomUUID().toString())
            .header("ce-type", "org.acme.travels.greetings.spanishname")
            .header("ce-source", "test")
            .body("{\"name\" : \"mary\"}")
        .when()
            .post("/")
        .then()
            .statusCode(204);
        events = eventSource.events();
        assertEquals(1, events.size());
        
        data = events.get(0);
        assertEquals("org.acme.travels.greetings.spanishname", data.source);
        assertEquals("org.acme.travels.greetings.endevent2", data.type); 
    }
    
    @Test
    public void testStartFunctionEndpointOtherPathStructured() {
        given()
            .contentType("application/cloudevents+json")
            .accept("application/cloudevents+json")
            .body("{\n"
                    + "  \"id\" : \"" + UUID.randomUUID().toString() + "\",\n"
                    + "  \"type\" : \"org.acme.travels.greetings\",\n"
                    + "  \"source\": \"test\",\n"
                    + "  \"datacontenttype\": \"application/json\",\n"
                    + "  \"data\": {\n"
                    + "    \"name\" : \"mary\"\n"
                    + "  }\n"
                    + "}")
        .when()
            .post("/")
        .then()
            .statusCode(204);
        List<EventData> events = eventSource.events();
        assertEquals(2, events.size());
        
        EventData data = events.get(0);
        assertEquals("org.acme.travels.greetings", data.source);
        assertEquals("org.acme.travels.greetings.spanishname", data.type);
        
        data = events.get(1);
        assertEquals("org.acme.travels.greetings", data.source);
        assertEquals("org.acme.travels.greetings.greeting", data.type);
          
        given()
            .contentType("application/cloudevents+json")
            .accept("application/cloudevents+json")
            .body("{\n"
                    + "  \"id\" : \"" + UUID.randomUUID().toString() + "\",\n"
                    + "  \"type\" : \"org.acme.travels.greetings.spanishname\",\n"
                    + "  \"source\": \"test\",\n"
                    + "  \"datacontenttype\": \"application/json\",\n"
                    + "  \"data\": {\n"
                    + "    \"name\" : \"mary\"\n"
                    + "  }\n"
                    + "}")
        .when()
            .post("/")
        .then()
            .statusCode(204);
        events = eventSource.events();
        assertEquals(1, events.size());
        
        data = events.get(0);
        assertEquals("org.acme.travels.greetings.spanishname", data.source);
        assertEquals("org.acme.travels.greetings.endevent2", data.type); 
    }
    // @formatter:on
}