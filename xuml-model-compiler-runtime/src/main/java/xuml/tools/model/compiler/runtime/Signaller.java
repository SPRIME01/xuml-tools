package xuml.tools.model.compiler.runtime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.EntityTransaction;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Cancellable;
import akka.actor.Props;
import scala.concurrent.ExecutionContext;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import xuml.tools.model.compiler.runtime.actor.RootActor;
import xuml.tools.model.compiler.runtime.message.Signal;

public class Signaller {

    private final ThreadLocal<Info> info = new ThreadLocal<Info>() {
        @Override
        protected Info initialValue() {
            return new Info();
        }
    };
    private final ActorSystem actorSystem = ActorSystem.create();
    private final ActorRef root = actorSystem.actorOf(Props.create(RootActor.class), "root");
    private final EntityManagerFactory emf;

    public Signaller(EntityManagerFactory emf, SignalProcessorListenerFactory listenerFactory) {
        this.emf = emf;
        root.tell(emf, root);
        if (listenerFactory != null)
            root.tell(listenerFactory, root);
    }

    public EntityManagerFactory getEntityManagerFactory() {
        return emf;
    }

    /**
     * Returns a new instance of type T using the given {@link CreationEvent}.
     * This is a synchronous creation using a newly created then closed
     * EntityManager for persisting the entity. If you need finer grained
     * control of commits then open your own entity manager and do the the
     * persist yourself.
     * 
     * @param cls
     * @param event
     * @return
     */
    public <T extends Entity<T>> T create(Class<T> cls, CreationEvent<T> event) {
        EntityManager em = null;
        EntityTransaction tx = null;
        T t;
        try {
            // TODO add before and after listener notifications for create event
            // (see EntityActor for listener example for non-creation events
            t = cls.newInstance();
        } catch (InstantiationException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        try {
            em = emf.createEntityManager();
            t.helper().setEntityManager(em);
            tx = em.getTransaction();
            tx.begin();
            t.event(event);
            em.persist(t);
            tx.commit();
            // only after successful commit do we send the signals to other
            // entities made during onEntry procedure.
            t.helper().sendQueuedSignals();
        } catch (RuntimeException e) {
            if (tx != null && tx.isActive())
                tx.rollback();
            throw e;
        } finally {
            t.helper().setEntityManager(null);
            if (em != null && em.isOpen())
                em.close();
        }
        return t;

    }

    public <T extends Entity<T>> void signal(String fromEntityUniqueId, Entity<T> entity,
            Event<T> event, Optional<Duration> delay) {
        signal(fromEntityUniqueId, entity, event, delay, Optional.<FiniteDuration> absent());
    }

    public <T extends Entity<T>> void signal(String fromEntityUniqueId, Entity<T> entity,
            Event<T> event, Long time, Optional<FiniteDuration> repeatInterval) {
        signal(fromEntityUniqueId, entity, event, getDelay(time), repeatInterval);
    }

    private Optional<Duration> getDelay(Long time) {
        long now = System.currentTimeMillis();
        if (time == null || time <= now)
            return Optional.absent();
        else
            return Optional.<Duration> of(Duration.create(time - now, TimeUnit.MILLISECONDS));
    }

    public <T extends Entity<T>> void signal(String fromEntityUniqueId, Entity<T> entity,
            Event<T> event, Optional<Duration> delay, Optional<FiniteDuration> repeatInterval) {
        Preconditions.checkNotNull(delay);
        Preconditions.checkNotNull(repeatInterval);
        long time;
        long now = System.currentTimeMillis();
        if (!delay.isPresent())
            time = now;
        else
            time = now + delay.get().toMillis();
        Optional<Long> repeatIntervalMs;
        if (repeatInterval.isPresent())
            repeatIntervalMs = Optional.of(repeatInterval.get().toMillis());
        else
            repeatIntervalMs = Optional.absent();

        @SuppressWarnings("unchecked")
        long id = persistSignal(fromEntityUniqueId, entity.getId(), (Class<T>) entity.getClass(),
                event, time, repeatIntervalMs);
        Signal<T> signal = new Signal<T>(fromEntityUniqueId, entity, event, id, time,
                repeatInterval);
        signal(signal);
    }

    private static class EntityEvent {
        String fromEntityUniqueId;
        String entityUniqueId;
        String eventSignature;

        EntityEvent(String fromEntityUniqueId, String entityUniqueId, String eventSignature) {
            super();
            this.fromEntityUniqueId = fromEntityUniqueId;
            this.entityUniqueId = entityUniqueId;
            this.eventSignature = eventSignature;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((entityUniqueId == null) ? 0 : entityUniqueId.hashCode());
            result = prime * result + ((eventSignature == null) ? 0 : eventSignature.hashCode());
            result = prime * result
                    + ((fromEntityUniqueId == null) ? 0 : fromEntityUniqueId.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            EntityEvent other = (EntityEvent) obj;
            if (entityUniqueId == null) {
                if (other.entityUniqueId != null)
                    return false;
            } else if (!entityUniqueId.equals(other.entityUniqueId))
                return false;
            if (eventSignature == null) {
                if (other.eventSignature != null)
                    return false;
            } else if (!eventSignature.equals(other.eventSignature))
                return false;
            if (fromEntityUniqueId == null) {
                if (other.fromEntityUniqueId != null)
                    return false;
            } else if (!fromEntityUniqueId.equals(other.fromEntityUniqueId))
                return false;
            return true;
        }

    }

    private final Map<EntityEvent, Cancellable> scheduleCancellers = Maps.newConcurrentMap();

    public <T> void cancelSignal(String fromEntityUniqueId, Entity<T> entity,
            String eventSignatureKey) {
        cancelSignal(fromEntityUniqueId, entity.uniqueId(), eventSignatureKey);
    }

    private <T> void signal(Signal<T> signal) {
        if (signalInitiatedFromEvent()) {
            info.get().getCurrentEntity().helper().queueSignal(signal);
        } else {
            long now = System.currentTimeMillis();
            long delayMs = (signal.getTime() == null ? now : signal.getTime()) - now;
            if (delayMs <= 0)
                root.tell(signal, root);
            else {
                // There can be at most one delayed signal of a given event
                // signature outstanding for each sender-receiver instance pair
                // at any one time. Mellor & Balcer p194.
                synchronized (this) {
                    EntityEvent key = cancelSignal(signal.getFromEntityUniqueId(),
                            signal.getEntity().uniqueId(), signal.getEvent().signatureKey());
                    Cancellable cancellable = schedulerSignal(signal, delayMs);
                    scheduleCancellers.put(key, cancellable);
                }
            }
        }
    }

    private <T> Cancellable schedulerSignal(Signal<T> signal, long delayMs) {
        ExecutionContext executionContext = actorSystem.dispatcher();
        if (!signal.getRepeatInterval().isPresent())
            return actorSystem.scheduler().scheduleOnce(
                    Duration.create(delayMs, TimeUnit.MILLISECONDS), root, signal, executionContext,
                    root);
        else
            return actorSystem.scheduler().schedule(Duration.create(delayMs, TimeUnit.MILLISECONDS),
                    signal.getRepeatInterval().get(), root, signal, executionContext, root);
    }

    private <T> EntityEvent cancelSignal(String fromEntityUniqueid, String toEntityUniqueId,
            String eventSignatureKey) {
        EntityEvent key = new EntityEvent(fromEntityUniqueid, toEntityUniqueId, eventSignatureKey);
        Cancellable current = scheduleCancellers.remove(key);
        if (current != null)
            current.cancel();
        return key;
    }

    @SuppressWarnings({ "unchecked" })
    public int sendSignalsInQueue() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        List<QueuedSignal> signals = em
                .createQuery(
                        "select s from " + QueuedSignal.class.getSimpleName() + " s order by id")
                .getResultList();
        em.getTransaction().commit();
        em.close();
        // close transaction before signalling because EntityActor will attempt
        // to delete the QueuedSignal within its transaction processing the
        // event
        for (QueuedSignal sig : signals) {
            signal(sig);
        }
        return signals.size();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void signal(QueuedSignal sig) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        System.out.println("sending " + sig);
        Event event = (Event) Util.toObject(sig.eventContent);
        Object id = Util.toObject(sig.idContent);
        Class<?> entityClass;
        entityClass = getClassForName(sig.entityClassName);
        Entity entity = (Entity) em.find(entityClass, id);
        em.getTransaction().commit();
        em.close();
        // TODO reschedule signals
        if (entity != null) {
            signal(new Signal(sig.fromEntityUniqueId, entity, event, sig.id));
        } else
            System.out.println(
                    "ENTITY NOT FOUND for entityClassName=" + sig.entityClassName + ",id=" + id);
    }

    private Class<?> getClassForName(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public <T extends Entity<T>> long persistSignal(String fromEntityUniqueId, Object id,
            Class<T> cls, Event<T> event, long time, Optional<Long> repeatIntervalMs) {
        byte[] idBytes = Util.toBytes(id);
        byte[] eventBytes = Util.toBytes(event);
        QueuedSignal signal = new QueuedSignal(idBytes, cls.getName(), event.getClass().getName(),
                eventBytes, time, repeatIntervalMs, fromEntityUniqueId);
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.persist(signal);
        em.getTransaction().commit();
        em.close();
        return signal.id;
    }

    private boolean signalInitiatedFromEvent() {
        return info.get().getCurrentEntity() != null;
    }

    public Info getInfo() {
        return info.get();
    }

    public void stop() {
        actorSystem.shutdown();
    }

    public void close() {
        emf.close();
    }
}
