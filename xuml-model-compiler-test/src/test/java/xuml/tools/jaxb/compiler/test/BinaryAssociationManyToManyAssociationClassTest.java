package xuml.tools.jaxb.compiler.test;

import static org.junit.Assert.assertEquals;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;

import many_to_many_association.A;
import many_to_many_association.B;
import many_to_many_association.Context;

import org.junit.Test;

import xuml.tools.util.database.DerbyUtil;

public class BinaryAssociationManyToManyAssociationClassTest {
	private static EntityManagerFactory emf;

	// @BeforeClass
	public static void setup() {
		DerbyUtil.disableDerbyLog();
		emf = Persistence
				.createEntityManagerFactory("many-to-many-association");
		Context.setEntityManagerFactory(emf);
	}

	// @AfterClass
	public static void shutdown() {
		emf.close();
	}

	@Test
	public void dummy() {
		// TODO remove and enable tests
	}

	// @Test
	public void testCanCreateManyToMany() {

		EntityManager em = emf.createEntityManager();
		em.getTransaction().begin();
		A a1 = A.create("thing").persist(em);
		A a2 = A.create("thing2").persist(em);
		B b1 = B.create("boo").persist(em);
		B b2 = B.create("boo2").persist(em);
		a1.getB().add(b1);
		a1.getB().add(b2);
		b1.getA().add(a1);
		b2.getA().add(a1);

		a2.getB().add(b1);
		a2.getB().add(b2);
		b1.getA().add(a2);
		b2.getA().add(a2);

		em.flush();
		assertEquals(2, a1.getC().size());

		em.getTransaction().commit();
		em.close();
	}
}