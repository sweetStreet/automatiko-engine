package io.automatik.engine.addons.persistence.db;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonTypeInfo.As;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import io.automatik.engine.api.marshalling.ObjectMarshallingStrategy;

public class JacksonObjectMarshallingStrategy implements ObjectMarshallingStrategy {

	private static final Logger logger = LoggerFactory.getLogger(JacksonObjectMarshallingStrategy.class);

	private ObjectMapper mapper;

	public JacksonObjectMarshallingStrategy() {
		this.mapper = new ObjectMapper().activateDefaultTyping(
				BasicPolymorphicTypeValidator.builder().allowIfSubType(Object.class).build(), DefaultTyping.EVERYTHING,
				As.PROPERTY);
	}

	@Override
	public boolean accept(Object object) {
		return true;
	}

	@Override
	public byte[] marshal(Context context, ObjectOutputStream os, Object object) throws IOException {
		return log(mapper.writeValueAsBytes(object));
	}

	@Override
	public Object unmarshal(String dataType, Context context, ObjectInputStream is, byte[] object,
			ClassLoader classloader) throws IOException, ClassNotFoundException {

		return mapper.readValue(log(object), Object.class);
	}

	@Override
	public Context createContext() {
		return null;
	}

	protected byte[] log(byte[] data) {
		logger.debug("Variable content:: {}", new String(data, StandardCharsets.UTF_8));

		return data;
	}
}
