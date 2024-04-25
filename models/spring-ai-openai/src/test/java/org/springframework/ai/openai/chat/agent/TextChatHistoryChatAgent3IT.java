/*
 * Copyright 2024 - 2024 the original author or authors.
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

package org.springframework.ai.openai.chat.agent;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;

import org.springframework.ai.chat.agent.ChatAgent;
import org.springframework.ai.chat.agent.DefaultChatAgent;
import org.springframework.ai.chat.history.ChatMemory;
import org.springframework.ai.chat.history.ChatMemoryAgentListener;
import org.springframework.ai.chat.history.ChatMemoryRetriever;
import org.springframework.ai.chat.history.InMemoryChatMemory;
import org.springframework.ai.chat.history.LastMaxTokenSizeContentTransformer;
import org.springframework.ai.chat.history.SystemPromptChatMemoryAugmentor;
import org.springframework.ai.chat.history.VectorStoreChatMemoryAgentListener;
import org.springframework.ai.chat.history.VectorStoreChatMemoryRetriever;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.transformer.PromptContext;
import org.springframework.ai.chat.prompt.transformer.QuestionContextAugmentor;
import org.springframework.ai.chat.prompt.transformer.TransformerContentType;
import org.springframework.ai.chat.prompt.transformer.VectorStoreRetriever;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.embedding.EmbeddingClient;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.RelevancyEvaluator;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.openai.OpenAiEmbeddingClient;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.reader.JsonReader;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(classes = TextChatHistoryChatAgent3IT.Config.class)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
public class TextChatHistoryChatAgent3IT {

	protected final Logger logger = LoggerFactory.getLogger(getClass());

	private static final String COLLECTION_NAME = "test_collection";

	private static final int QDRANT_GRPC_PORT = 6334;

	@Container
	static QdrantContainer qdrantContainer = new QdrantContainer("qdrant/qdrant:v1.7.4");

	@Autowired
	ChatAgent chatAgent;

	@Autowired
	RelevancyEvaluator relevancyEvaluator;

	@Autowired
	VectorStore vectorStore;

	@Value("classpath:/data/acme/bikes.json")
	private Resource bikesResource;

	void loadData() {

		var metadataEnricher = new DocumentTransformer() {

			@Override
			public List<Document> apply(List<Document> documents) {
				documents.forEach(d -> {
					Map<String, Object> metadata = d.getMetadata();
					metadata.put(TransformerContentType.EXTERNAL_KNOWLEDGE, "true");
				});

				return documents;
			}

		};

		JsonReader jsonReader = new JsonReader(bikesResource, "name", "price", "shortDescription", "description");
		var textSplitter = new TokenTextSplitter();
		vectorStore.accept(metadataEnricher.apply(textSplitter.apply(jsonReader.get())));
	}

	// @Autowired
	// StreamingChatAgent streamingChatAgent;

	@Test
	void memoryChatAgent() {

		loadData();

		var prompt = new Prompt(new UserMessage("My name is Christian and I like mountain bikes."));
		PromptContext promptContext = new PromptContext(prompt);

		var agentResponse1 = this.chatAgent.call(promptContext);

		logger.info("Response1: " + agentResponse1.getChatResponse().getResult().getOutput().getContent());

		var agentResponse2 = this.chatAgent.call(new PromptContext(
				new Prompt(new String("What is my name and what bike model would you suggest for me?"))));
		logger.info("Response2: " + agentResponse2.getChatResponse().getResult().getOutput().getContent());

		logger.info(agentResponse2.getPromptContext().getContents().toString());
		assertThat(agentResponse2.getChatResponse().getResult().getOutput().getContent()).contains("Christian",
				"mountain bikes");

		EvaluationResponse evaluationResponse = this.relevancyEvaluator.evaluate(new EvaluationRequest(agentResponse2));
		logger.info("" + evaluationResponse);
	}

	@SpringBootConfiguration
	static class Config {

		@Bean
		public ChatMemory chatHistory() {
			return new InMemoryChatMemory();
		}

		@Bean
		public OpenAiApi chatCompletionApi() {
			return new OpenAiApi(System.getenv("OPENAI_API_KEY"));
		}

		@Bean
		public OpenAiChatClient openAiClient(OpenAiApi openAiApi) {
			return new OpenAiChatClient(openAiApi);
		}

		@Bean
		public OpenAiEmbeddingClient embeddingClient(OpenAiApi openAiApi) {
			return new OpenAiEmbeddingClient(openAiApi);
		}

		@Bean
		public VectorStore qdrantVectorStore(EmbeddingClient embeddingClient) {
			QdrantClient qdrantClient = new QdrantClient(QdrantGrpcClient
				.newBuilder(qdrantContainer.getHost(), qdrantContainer.getMappedPort(QDRANT_GRPC_PORT), false)
				.build());
			return new QdrantVectorStore(qdrantClient, COLLECTION_NAME, embeddingClient);
		}

		@Bean
		public TokenCountEstimator tokenCountEstimator() {
			return new JTokkitTokenCountEstimator();
		}

		@Bean
		public ChatAgent memoryChatAgent(OpenAiChatClient chatClient, VectorStore vectorStore,
				TokenCountEstimator tokenCountEstimator, ChatMemory chatHistory) {

			return DefaultChatAgent.builder(chatClient)
				.withRetrievers(List.of(new VectorStoreRetriever(vectorStore, SearchRequest.defaults()),
						new ChatMemoryRetriever(chatHistory, Map.of(TransformerContentType.SHORT_TERM_MEMORY, "")),
						new VectorStoreChatMemoryRetriever(vectorStore, 10,
								Map.of(TransformerContentType.LONG_TERM_MEMORY, ""))))

				.withDocumentPostProcessors(List.of(
						new LastMaxTokenSizeContentTransformer(tokenCountEstimator, 1000,
								Set.of(TransformerContentType.SHORT_TERM_MEMORY)),
						new LastMaxTokenSizeContentTransformer(tokenCountEstimator, 1000,
								Set.of(TransformerContentType.LONG_TERM_MEMORY)),
						new LastMaxTokenSizeContentTransformer(tokenCountEstimator, 2000,
								Set.of(TransformerContentType.EXTERNAL_KNOWLEDGE))))
				.withAugmentors(List.of(new QuestionContextAugmentor(),
						new SystemPromptChatMemoryAugmentor(
								"""
										Use the long term conversation history from the LONG TERM HISTORY section to provide accurate answers.

										LONG TERM HISTORY:
										{history}
											""",
								Set.of(TransformerContentType.LONG_TERM_MEMORY)),
						new SystemPromptChatMemoryAugmentor(Set.of(TransformerContentType.SHORT_TERM_MEMORY))))

				.withChatAgentListeners(List.of(new ChatMemoryAgentListener(chatHistory),
						new VectorStoreChatMemoryAgentListener(vectorStore,
								Map.of(TransformerContentType.LONG_TERM_MEMORY, ""))))
				.build();
		}

		// @Bean
		// public StreamingChatAgent memoryStreamingChatAgent(OpenAiChatClient
		// streamingChatClient,
		// VectorStore vectorStore, TokenCountEstimator tokenCountEstimator, ChatHistory
		// chatHistory) {

		// return DefaultStreamingChatAgent.builder(streamingChatClient)
		// .withRetrievers(List.of(new ChatHistoryRetriever(chatHistory), new
		// DocumentChatHistoryRetriever(vectorStore, 10)))
		// .withDocumentPostProcessors(List.of(new
		// LastMaxTokenSizeContentTransformer(tokenCountEstimator, 1000)))
		// .withAugmentors(List.of(new TextChatHistoryAugmenter()))
		// .withChatAgentListeners(List.of(new ChatHistoryAgentListener(chatHistory), new
		// DocumentChatHistoryAgentListener(vectorStore)))
		// .build();
		// }

		@Bean
		public RelevancyEvaluator relevancyEvaluator(OpenAiChatClient chatClient) {
			return new RelevancyEvaluator(chatClient);
		}

	}

}
