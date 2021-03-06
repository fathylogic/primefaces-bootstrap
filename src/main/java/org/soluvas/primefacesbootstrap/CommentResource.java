package org.soluvas.primefacesbootstrap;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.List;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.jcr.Node;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.jackrabbit.core.TransientRepository;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.soluvas.json.JsonUtils;
import org.soluvas.push.CollectionAdd;
import org.soluvas.push.CollectionDelete;
import org.soluvas.push.CollectionUpdate;
import org.soluvas.push.Notification;
import org.soluvas.push.PushMessage;

import com.google.common.base.Function;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

/**
 * @author ceefour
 */
@Singleton @Startup
@Path("/comment")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class CommentResource {

	private static transient Logger log = LoggerFactory
			.getLogger(CommentResource.class);
	private Session session;
	private Node commentRoot;
	private TransientRepository repository;
	private Connection conn;
	private Channel channel;
	
	private static boolean started = false;
	
	@PostConstruct
	public void init() throws Exception {
		log.info("Starting CommentResource");
		repository = new TransientRepository(new File("/home/ceefour/git/primefaces-bootstrap/jcr-data"));
		try {
			session = repository.login(new SimpleCredentials("TestUser", "".toCharArray())); 
			
			String user = session.getUserID();
			String name = repository.getDescriptor(Repository.REP_NAME_DESC);
			log.info("Logged in as {} to a {} repository.", user, name);
			
			Node root = session.getRootNode();
			if (!root.hasNode("comment")) {
				log.info("Creating comment root node under {}", root);
				commentRoot = root.addNode("comment");
				log.info("Created comment root node: {} - {}", commentRoot.getIdentifier(), commentRoot.getPath());
				session.save();
			} else {
				commentRoot = root.getNode("comment");
			}
			
			String messagingUri = "amqp://guest:guest@localhost/%2F"; 
			log.info("Connecting to {}", messagingUri);
			ConnectionFactory connFactory = new ConnectionFactory();
			connFactory.setUri(messagingUri);
			conn = connFactory.newConnection();
			channel = conn.createChannel();
			started = true;
		} catch (Exception e) {
			if (session != null && session.isLive())
				session.logout();
			if (repository != null)
				repository.shutdown();
			throw new RuntimeException("init", e);
		}
	}

	@PreDestroy
	public void destroy() throws Exception {
		log.debug("Destroying CommentResource");
		started = false;
		try {
			log.debug("Destroying channel");
			channel.close();
			log.debug("Destroying connection");
			conn.close();
		} catch (Exception e) {
			log.warn("Close AMQP", e);
		}
		log.debug("Destroying session");
		if (session != null && session.isLive())
			session.logout();
		log.debug("Destroying repository");
		if (repository != null)
			repository.shutdown();
	}
	
	static long notificationNumber = 1L;
//	@Schedule(hour="*", minute="*", second="*/1", persistent=false)
	public void giveNotification() {
		if (!started)
			return;
		
		log.debug("Generating two notifications {}", notificationNumber);
		Notification notification = new Notification("Count A " + notificationNumber);
		sendToProductTopic("zibalabel_t01", new CollectionAdd<Notification>("notification", notification));
		notificationNumber++;
		
		notification = new Notification("Count B " + notificationNumber);
		sendToProductTopic("zibalabel_t01", new CollectionAdd<Notification>("notification", notification));
		notificationNumber++;
	}
	
	protected void sendToProductTopic(String productId, PushMessage push) {
		try {
			channel.basicPublish("amq.topic", "product." + productId, new AMQP.BasicProperties.Builder().build(), JsonUtils.asJson(push).getBytes());
		} catch (IOException e) {
			throw new RuntimeException("Publish " + push, e);
		}
//		conn.send(JsonUtils.asJson(push), "jms.topic.product");
//		producer.sendBodyAndHeader("jms:topic:product?deliveryPersistent=false", ExchangePattern.InOnly,
//				JsonUtils.asJson(push), "productId", "zibalabel_t01");
	}

	@GET
	public List<Comment> findAll() throws RepositoryException {
		log.info("find all comments");
		List<Comment> comments = Lists.newArrayList( Iterators.transform(commentRoot.getNodes(), new Function<Node, Comment>() {
			@Override
			public Comment apply(Node node) {
				return new Comment(node);
			}
		}) );
		return comments;
	}

	@POST
	public Response create(Comment comment) throws RepositoryException {
		log.info("create comment {}", comment);
		
		Node commentNode = commentRoot.addNode(comment.getId());
		commentNode.setProperty("authorName", comment.getAuthorName());
		commentNode.setProperty("body", comment.getBody());
		commentNode.setProperty("created", comment.getCreated().toGregorianCalendar());
		commentNode.setProperty("lastModified", comment.getLastModified().toGregorianCalendar());
		session.save();
		
		CollectionAdd<Comment> push = new CollectionAdd<Comment>("comment", comment);
		sendToProductTopic("zibalabel_t01", push);
		
		Notification notification = new Notification(comment.getAuthorName() +" berkomentar: "+ comment.getBody());
		sendToProductTopic("zibalabel_t01", new CollectionAdd<Notification>("notification", notification));
		
		return Response.created(URI.create(comment.getId()))
				.entity(comment).build();
	}

	@GET @Path("/{commentId}")
	public Comment findOne(@PathParam("commentId") String commentId) {
		log.info("get comment {}", commentId);
		try {
			Node commentNode = commentRoot.getNode(commentId);
			Comment comment = new Comment(commentNode);
			return comment;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@DELETE @Path("/{commentId}")
	public Response delete(@PathParam("commentId") String commentId) {
		log.info("delete comment {}", commentId);
		try {
			Node commentNode = commentRoot.getNode(commentId);
			Comment comment = new Comment(commentNode);
			commentNode.remove();
			session.save();
			
			CollectionDelete push = new CollectionDelete("comment", commentId);
			sendToProductTopic("zibalabel_t01", push);
			
			Notification notification = new Notification("Komentar dari "+ comment.getAuthorName() +" dihapus.");
			sendToProductTopic("zibalabel_t01", new CollectionAdd<Notification>("notification", notification));
			
			return Response.noContent().build();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@PUT @Path("/{commentId}")
	public Comment update(@PathParam("commentId") String commentId, Comment comment) {
		log.info("update comment {} with {}", commentId, comment);
		try {
			Node commentNode = commentRoot.getNode(commentId);
			commentNode.setProperty("body", comment.getBody());
			commentNode.setProperty("lastModified", new DateTime().toGregorianCalendar());
			session.save();
			
			Comment updatedComment = new Comment(commentNode);
			
			CollectionUpdate<Comment> push = new CollectionUpdate<Comment>("comment", commentId, updatedComment);
			sendToProductTopic("zibalabel_t01", push);
			
			Notification notification = new Notification(comment.getAuthorName() +" menyunting komentarnya.");
			sendToProductTopic("zibalabel_t01", new CollectionAdd<Notification>("notification", notification));
			
			return comment;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

}
