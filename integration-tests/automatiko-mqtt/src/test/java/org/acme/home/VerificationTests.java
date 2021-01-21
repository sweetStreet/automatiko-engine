package org.acme.home;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import javax.enterprise.inject.Any;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.junit.jupiter.api.Test;

import io.automatiko.engine.api.event.EventPublisher;
import io.automatiko.engine.services.event.impl.CountDownProcessInstanceEventPublisher;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.smallrye.reactive.messaging.connectors.InMemoryConnector;
import io.smallrye.reactive.messaging.connectors.InMemorySource;
import io.smallrye.reactive.messaging.mqtt.MqttMessage;

@QuarkusTest
public class VerificationTests {
 // @formatter:off
    
    @Inject 
    @Any
    InMemoryConnector connector;
    
    private CountDownProcessInstanceEventPublisher execCounter = new CountDownProcessInstanceEventPublisher();
    
    @Produces
    @Singleton
    public EventPublisher publisher() {
        return execCounter;
    }
    
    @Test
    public void testProcessExecution() throws InterruptedException {
        String humidity = "{\"timestamp\":1, \"value\" : 45.0, \"location\":\"kitchen\"}";
        String temperature = "{\"timestamp\":1, \"value\" : 29.0, \"location\":\"kitchen\"}";
        
        InMemorySource<MqttMessage<byte[]>> channelT = connector.source("home-x-temperature");
        
        InMemorySource<MqttMessage<byte[]>> channelH = connector.source("home-x-humidity");
        
        execCounter.reset(2);
        channelT.send(MqttMessage.of("home/kitchen/temperature", temperature.getBytes()));
        channelH.send(MqttMessage.of("home/kitchen/humidity", humidity.getBytes()));
        execCounter.waitTillCompletion(5);
        
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/climate")
        .then().statusCode(200)
            .body("$.size()", is(1));
        
        Map data = given()
            .accept(ContentType.JSON)
        .when()
            .get("/climate/kitchen")
        .then()
            .statusCode(200).body("id", is("kitchen")).extract().as(Map.class);
        
        List<?> tempBucket = (List<?>) data.get("temperatureBucket");
        List<?> humidityBucket = (List<?>) data.get("humidityBucket");
        
        assertEquals(1, tempBucket.size());
        assertEquals(1, humidityBucket.size());
        
        // let's push data for living room
        humidity = "{\"timestamp\":1, \"value\" : 45.0, \"location\":\"livingroom\"}";
        temperature = "{\"timestamp\":1, \"value\" : 29.0, \"location\":\"livingroom\"}";
        
        execCounter.reset(2);
        channelT.send(MqttMessage.of("home/livingroom/temperature", temperature.getBytes()));
        channelH.send(MqttMessage.of("home/livingroom/humidity", humidity.getBytes()));
        execCounter.waitTillCompletion(5);
        
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/climate")
        .then().statusCode(200)
            .body("$.size()", is(2));
        
        data = given()
            .accept(ContentType.JSON)
        .when()
            .get("/climate/livingroom")
        .then()
            .statusCode(200).body("id", is("livingroom")).extract().as(Map.class);
        
        tempBucket = (List<?>) data.get("temperatureBucket");
        humidityBucket = (List<?>) data.get("humidityBucket");
        
        assertEquals(1, tempBucket.size());
        assertEquals(1, humidityBucket.size());
        
        // abort instance for kitchen
        given()
            .accept(ContentType.JSON)
        .when()
            .delete("/climate/kitchen")
        .then()
            .statusCode(200);
        
        // abort instance for livingroom
        given()
            .accept(ContentType.JSON)
        .when()
            .delete("/climate/livingroom")
        .then()
            .statusCode(200);
        
        given()
            .accept(ContentType.JSON)
        .when()
            .get("/climate")
        .then().statusCode(200)
            .body("$.size()", is(0));
    }
    
 // @formatter:on
}
