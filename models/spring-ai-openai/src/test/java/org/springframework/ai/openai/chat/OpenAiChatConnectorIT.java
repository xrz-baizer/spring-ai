/*
 * Copyright 2023 - 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.ai.openai.chat;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Media;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.ListOutputConverter;
import org.springframework.ai.converter.MapOutputConverter;
import org.springframework.ai.model.function.FunctionCallbackWrapper;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiTestConfiguration;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.tool.MockWeatherService;
import org.springframework.ai.openai.testutils.AbstractIT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.MimeTypeUtils;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = OpenAiTestConfiguration.class)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class OpenAiChatConnectorIT extends AbstractIT {

	private static final Logger logger = LoggerFactory.getLogger(OpenAiChatConnectorIT.class);

	@Value("classpath:/prompts/system-message.st")
	private Resource systemResource;

	@Test
	void roleTest() {
		UserMessage userMessage = new UserMessage(
				"Tell me about 3 famous pirates from the Golden Age of Piracy and what they did.");
		SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(systemResource);
		Message systemMessage = systemPromptTemplate.createMessage(Map.of("name", "Bob", "voice", "pirate"));
		Prompt prompt = new Prompt(List.of(userMessage, systemMessage));
		ChatResponse response = chatConnector.execute(prompt);
		assertThat(response.getResults()).hasSize(1);
		assertThat(response.getResults().get(0).getOutput().getContent()).contains("Blackbeard");
		// needs fine tuning... evaluateQuestionAndAnswer(request, response, false);
	}

	@Test
	void listOutputConverter() {
		DefaultConversionService conversionService = new DefaultConversionService();
		ListOutputConverter outputConverter = new ListOutputConverter(conversionService);

		String format = outputConverter.getFormat();
		String template = """
				List five {subject}
				{format}
				""";
		PromptTemplate promptTemplate = new PromptTemplate(template,
				Map.of("subject", "ice cream flavors", "format", format));
		Prompt prompt = new Prompt(promptTemplate.createMessage());
		Generation generation = this.chatConnector.execute(prompt).getResult();

		List<String> list = outputConverter.convert(generation.getOutput().getContent());
		assertThat(list).hasSize(5);

	}

	@Test
	void mapOutputConverter() {
		MapOutputConverter outputConverter = new MapOutputConverter();

		String format = outputConverter.getFormat();
		String template = """
				Provide me a List of {subject}
				{format}
				""";
		PromptTemplate promptTemplate = new PromptTemplate(template,
				Map.of("subject", "an array of numbers from 1 to 9 under they key name 'numbers'", "format", format));
		Prompt prompt = new Prompt(promptTemplate.createMessage());
		Generation generation = chatConnector.execute(prompt).getResult();

		Map<String, Object> result = outputConverter.convert(generation.getOutput().getContent());
		assertThat(result.get("numbers")).isEqualTo(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9));

	}

	@Test
	void beanOutputConverter() {

		BeanOutputConverter<ActorsFilms> outputConverter = new BeanOutputConverter<>(ActorsFilms.class);

		String format = outputConverter.getFormat();
		String template = """
				Generate the filmography for a random actor.
				{format}
				""";
		PromptTemplate promptTemplate = new PromptTemplate(template, Map.of("format", format));
		Prompt prompt = new Prompt(promptTemplate.createMessage());
		Generation generation = chatConnector.execute(prompt).getResult();

		ActorsFilms actorsFilms = outputConverter.convert(generation.getOutput().getContent());
	}

	record ActorsFilmsRecord(String actor, List<String> movies) {
	}

	@Test
	void beanOutputConverterRecords() {

		BeanOutputConverter<ActorsFilmsRecord> outputConverter = new BeanOutputConverter<>(ActorsFilmsRecord.class);

		String format = outputConverter.getFormat();
		String template = """
				Generate the filmography of 5 movies for Tom Hanks.
				{format}
				""";
		PromptTemplate promptTemplate = new PromptTemplate(template, Map.of("format", format));
		Prompt prompt = new Prompt(promptTemplate.createMessage());
		Generation generation = chatConnector.execute(prompt).getResult();

		ActorsFilmsRecord actorsFilms = outputConverter.convert(generation.getOutput().getContent());
		logger.info("" + actorsFilms);
		assertThat(actorsFilms.actor()).isEqualTo("Tom Hanks");
		assertThat(actorsFilms.movies()).hasSize(5);
	}

	@Test
	void beanStreamOutputConverterRecords() {

		BeanOutputConverter<ActorsFilmsRecord> outputConverter = new BeanOutputConverter<>(ActorsFilmsRecord.class);

		String format = outputConverter.getFormat();
		String template = """
				Generate the filmography of 5 movies for Tom Hanks.
				{format}
				""";
		PromptTemplate promptTemplate = new PromptTemplate(template, Map.of("format", format));
		Prompt prompt = new Prompt(promptTemplate.createMessage());

		String generationTextFromStream = streamingChatClient.stream(prompt)
			.collectList()
			.block()
			.stream()
			.map(ChatResponse::getResults)
			.flatMap(List::stream)
			.map(Generation::getOutput)
			.map(AssistantMessage::getContent)
			.collect(Collectors.joining());

		ActorsFilmsRecord actorsFilms = outputConverter.convert(generationTextFromStream);
		logger.info("" + actorsFilms);
		assertThat(actorsFilms.actor()).isEqualTo("Tom Hanks");
		assertThat(actorsFilms.movies()).hasSize(5);
	}

	@Test
	void functionExecuteTest() {

		UserMessage userMessage = new UserMessage("What's the weather like in San Francisco, Tokyo, and Paris?");

		List<Message> messages = new ArrayList<>(List.of(userMessage));

		var promptOptions = OpenAiChatOptions.builder()
			.withModel(OpenAiApi.ChatModel.GPT_4_TURBO_PREVIEW.getValue())
			.withFunctionCallbacks(List.of(FunctionCallbackWrapper.builder(new MockWeatherService())
				.withName("getCurrentWeather")
				.withDescription("Get the weather in location")
				.withResponseConverter((response) -> "" + response.temp() + response.unit())
				.build()))
			.build();

		ChatResponse response = chatConnector.execute(new Prompt(messages, promptOptions));

		logger.info("Response: {}", response);

		assertThat(response.getResult().getOutput().getContent()).containsAnyOf("30.0", "30");
		assertThat(response.getResult().getOutput().getContent()).containsAnyOf("10.0", "10");
		assertThat(response.getResult().getOutput().getContent()).containsAnyOf("15.0", "15");
	}

	@Test
	void streamFunctionExecuteTest() {

		UserMessage userMessage = new UserMessage("What's the weather like in San Francisco, Tokyo, and Paris?");

		List<Message> messages = new ArrayList<>(List.of(userMessage));

		var promptOptions = OpenAiChatOptions.builder()
			// .withModel(OpenAiApi.ChatModel.GPT_4_TURBO_PREVIEW.getValue())
			.withFunctionCallbacks(List.of(FunctionCallbackWrapper.builder(new MockWeatherService())
				.withName("getCurrentWeather")
				.withDescription("Get the weather in location")
				.withResponseConverter((response) -> "" + response.temp() + response.unit())
				.build()))
			.build();

		Flux<ChatResponse> response = streamingChatClient.stream(new Prompt(messages, promptOptions));

		String content = response.collectList()
			.block()
			.stream()
			.map(ChatResponse::getResults)
			.flatMap(List::stream)
			.map(Generation::getOutput)
			.map(AssistantMessage::getContent)
			.collect(Collectors.joining());
		logger.info("Response: {}", content);

		assertThat(content).containsAnyOf("30.0", "30");
		assertThat(content).containsAnyOf("10.0", "10");
		assertThat(content).containsAnyOf("15.0", "15");
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "gpt-4-vision-preview", "gpt-4o" })
	void multiModalityEmbeddedImage(String modelName) throws IOException {

		var imageData = new ClassPathResource("/test.png");

		var userMessage = new UserMessage("Explain what do you see on this picture?",
				List.of(new Media(MimeTypeUtils.IMAGE_PNG, imageData)));

		var response = chatConnector
			.execute(new Prompt(List.of(userMessage), OpenAiChatOptions.builder().withModel(modelName).build()));

		logger.info(response.getResult().getOutput().getContent());
		assertThat(response.getResult().getOutput().getContent()).contains("bananas", "apple");
		assertThat(response.getResult().getOutput().getContent()).containsAnyOf("bowl", "basket");
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "gpt-4-vision-preview", "gpt-4o" })
	void multiModalityImageUrl(String modelName) throws IOException {

		var userMessage = new UserMessage("Explain what do you see on this picture?", List
			.of(new Media(MimeTypeUtils.IMAGE_PNG,
					new URL("https://docs.spring.io/spring-ai/reference/1.0-SNAPSHOT/_images/multimodal.test.png"))));

		ChatResponse response = chatConnector
			.execute(new Prompt(List.of(userMessage), OpenAiChatOptions.builder().withModel(modelName).build()));

		logger.info(response.getResult().getOutput().getContent());
		assertThat(response.getResult().getOutput().getContent()).contains("bananas", "apple");
		assertThat(response.getResult().getOutput().getContent()).containsAnyOf("bowl", "basket");
	}

	@Test
	void streamingMultiModalityImageUrl() throws IOException {

		var userMessage = new UserMessage("Explain what do you see on this picture?", List
			.of(new Media(MimeTypeUtils.IMAGE_PNG,
					new URL("https://docs.spring.io/spring-ai/reference/1.0-SNAPSHOT/_images/multimodal.test.png"))));

		Flux<ChatResponse> response = streamingChatClient.stream(new Prompt(List.of(userMessage),
				OpenAiChatOptions.builder().withModel(OpenAiApi.ChatModel.GPT_4_VISION_PREVIEW.getValue()).build()));

		String content = response.collectList()
			.block()
			.stream()
			.map(ChatResponse::getResults)
			.flatMap(List::stream)
			.map(Generation::getOutput)
			.map(AssistantMessage::getContent)
			.collect(Collectors.joining());
		logger.info("Response: {}", content);
		assertThat(content).contains("bananas", "apple", "bowl");
	}

}