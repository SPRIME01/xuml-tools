package xuml.tools.jaxb.compiler.actor;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;

import xuml.tools.jaxb.compiler.Entity;
import xuml.tools.jaxb.compiler.message.CloseEntityActor;
import xuml.tools.jaxb.compiler.message.Signal;
import xuml.tools.jaxb.compiler.message.StopEntityActor;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class EntityActor extends UntypedActor {

	private EntityManagerFactory emf;
	private EntityManager em;
	private EntityTransaction tx;
	private boolean closed = false;
	private final LoggingAdapter log;

	public EntityActor() {
		log = Logging.getLogger(getContext().system(), this);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public void onReceive(Object message) throws Exception {
		log.info("received message " + message.getClass().getName());
		if (message instanceof EntityManagerFactory)
			handleMessage((EntityManagerFactory) message);
		else if (message instanceof Signal) {
			handleMessage((Signal) message);
		} else if (message instanceof StopEntityActor) {
			getContext().stop(getSelf());
		}
	}

	@SuppressWarnings("unchecked")
	private void handleMessage(@SuppressWarnings("rawtypes") Signal signal) {
		if (closed) {
			// if this actor is marked as closed then bounce all signal messages
			// back to sender
			getSender().tell(signal);
		} else if (emf != null) {
			// otherwise perform the event on the entity after it has been
			// refreshed within the scope of the current entity manager
			startTransaction();
			Entity<?> entity = signal.getEntity();
			em.refresh(signal.getEntity());
			entity.event(signal.getEvent());
			commit();
			getSender().tell(new CloseEntityActor(signal.getEntity()));
			// only after successful commit do we send the signals to other
			// entities made during onEntry procedure.
			entity.helper().sendQueuedSignals();
			closed = true;
		}
	}

	private void handleMessage(EntityManagerFactory message) {
		this.emf = message;
	}

	private void commit() {
		if (em != null) {
			tx.commit();
			em.close();
			em = null;
			tx = null;
			log.info("commited");
		}
	}

	private void startTransaction() {
		if (em == null) {
			em = emf.createEntityManager();
			tx = em.getTransaction();
			tx.begin();
			log.info("started transaction");
		}
	}
}
