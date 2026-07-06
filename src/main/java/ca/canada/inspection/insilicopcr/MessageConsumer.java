package ca.canada.inspection.insilicopcr;

import java.util.concurrent.BlockingQueue;
import java.util.List;
import java.util.ArrayList;

import javafx.animation.AnimationTimer;
import javafx.scene.control.TextArea;

public class MessageConsumer extends AnimationTimer{

	private final BlockingQueue<String> messageQueue;
	private final TextArea textArea;

	public MessageConsumer(BlockingQueue<String> messageQueue, TextArea textArea) {
		this.messageQueue = messageQueue;
		this.textArea = textArea;
	}

	@Override
	public void handle(long now) {
		List<String> messages = new ArrayList<String>();
		messageQueue.drainTo(messages);
		messages.forEach(msg -> textArea.appendText("\n" + msg));
	}
}
