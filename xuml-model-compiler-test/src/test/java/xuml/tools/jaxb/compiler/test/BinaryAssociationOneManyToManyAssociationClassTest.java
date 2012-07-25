package xuml.tools.jaxb.compiler.test;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import one_many_to_many_association.B;
import one_many_to_many_association.Context;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import xuml.tools.model.compiler.runtime.RelationshipNotEstablishedException;
import xuml.tools.util.database.DerbyUtil;

public class BinaryAssociationOneManyToManyAssociationClassTest {

	@BeforeClass
	public static void setup() {
		DerbyUtil.disableDerbyLog();
		EntityManagerFactory emf = Persistence
				.createEntityManagerFactory("one-many-to-many-association");
		Context.setEntityManagerFactory(emf);
	}

	@AfterClass
	public static void shutdown() {
		Context.close();
	}

	// TODO many more tests can be added here

	@Test(expected = RelationshipNotEstablishedException.class)
	public void testCannotCreateBWithoutInstanceOfAViaC() {

		EntityManager em = Context.createEntityManager();
		try {
			em.getTransaction().begin();
			B.create("boo").persist(em);
		} finally {
			em.close();
		}
	}
}
