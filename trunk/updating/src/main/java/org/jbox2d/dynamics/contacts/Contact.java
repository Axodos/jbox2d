package org.jbox2d.dynamics.contacts;

import java.util.Stack;

import org.jbox2d.collision.AABB;
import org.jbox2d.collision.shapes.CircleShape;
import org.jbox2d.collision.shapes.Shape;
import org.jbox2d.common.Settings;
import org.jbox2d.common.Sweep;
import org.jbox2d.common.Transform;
import org.jbox2d.dynamics.Body;
import org.jbox2d.dynamics.Fixture;
import org.jbox2d.pooling.SingletonPool;
import org.jbox2d.pooling.TLManifold;
import org.jbox2d.pooling.TLTOIInput;
import org.jbox2d.pooling.stacks.TLStack;
import org.jbox2d.structs.collision.ContactID;
import org.jbox2d.structs.collision.Manifold;
import org.jbox2d.structs.collision.ManifoldPoint;
import org.jbox2d.structs.collision.TOIInput;
import org.jbox2d.structs.collision.WorldManifold;
import org.jbox2d.structs.collision.shapes.ShapeType;
import org.jbox2d.structs.dynamics.contacts.ContactCreator;
import org.jbox2d.structs.dynamics.contacts.ContactEdge;
import org.jbox2d.structs.dynamics.contacts.ContactRegister;

// updated to rev 100
/**
 * The class manages contact between two shapes. A contact exists for each overlapping
 * AABB in the broad-phase (except if filtered). Therefore a contact object may exist
 * that has no contact points.
 *
 * @author daniel
 */
public abstract class Contact {
	// Flags stored in m_flags
	// Used when crawling contact graph when forming islands.
	public static final int ISLAND_FLAG		= 0x0001;
    // Set when the shapes are touching.
	public static final int TOUCHING_FLAG	= 0x0002;
	// This contact can be disabled (by user)
	public static final int ENABLED_FLAG		= 0x0004;
	// This contact needs filtering because a fixture filter was changed.
	public static final int FILTER_FLAG			= 0x0008;
	// This bullet contact had a TOI event
	public static final int BULLET_HIT_FLAG		= 0x0010;
	
	public static final ContactRegister[][] s_registers = new ContactRegister[ShapeType.TYPE_COUNT][ShapeType.TYPE_COUNT];
	public static boolean s_initialized = false;
	
	public static void addType(ContactCreator creator, ShapeType type1, ShapeType type2){
		ContactRegister register = new ContactRegister();
		register.creator = creator;
		register.primary = true;
		s_registers[type1.intValue][type2.intValue] = register;
		
		if(type1 != type2){
			ContactRegister register2 = new ContactRegister();
			register2.creator = creator;
			register.primary = false;
			s_registers[type2.intValue][type1.intValue] = register;
		}
	}
	
	public static void initializeRegisters(){
		addType(new ContactCreator() {
			private final TLStack<Contact> stack = new TLStack<Contact>();
			public void contactDestroyFcn(Contact contact) {
				stack.get().push(contact);
			}
			public Contact contactCreateFcn(Fixture fixtureA, Fixture fixtureB) {
				Stack<Contact> s = stack.get();
				if(s.isEmpty()){
					s.push(new CircleContact());
					s.push(new CircleContact());
					s.push(new CircleContact());
				}
				Contact c = s.pop();
				c.init(fixtureA, fixtureB);
				return c;
			}
		},ShapeType.CIRCLE, ShapeType.CIRCLE);
		addType(new ContactCreator() {
			private final TLStack<Contact> stack = new TLStack<Contact>();
			public void contactDestroyFcn(Contact contact) {
				stack.get().push(contact);
			}
			public Contact contactCreateFcn(Fixture fixtureA, Fixture fixtureB) {
				Stack<Contact> s = stack.get();
				if(s.isEmpty()){
					s.push(new PolygonAndCircleContact());
					s.push(new PolygonAndCircleContact());
					s.push(new PolygonAndCircleContact());
				}
				Contact c = s.pop();
				c.init(fixtureA, fixtureB);
				return c;
			}
		},ShapeType.POLYGON, ShapeType.CIRCLE);
		addType(new ContactCreator() {
			private final TLStack<Contact> stack = new TLStack<Contact>();
			public void contactDestroyFcn(Contact contact) {
				stack.get().push(contact);
			}
			public Contact contactCreateFcn(Fixture fixtureA, Fixture fixtureB) {
				Stack<Contact> s = stack.get();
				if(s.isEmpty()){
					s.push(new PolygonContact());
					s.push(new PolygonContact());
					s.push(new PolygonContact());
				}
				Contact c = s.pop();
				c.init(fixtureA, fixtureB);
				return c;
			}
		}, ShapeType.POLYGON, ShapeType.POLYGON);
	}
	
	public static Contact create(Fixture fixtureA, Fixture fixtureB){
		if(s_initialized == false){
			initializeRegisters();
			s_initialized = true;
		}
		
		ShapeType type1 = fixtureA.getType();
		ShapeType type2 = fixtureB.getType();
		
		ContactCreator creator = s_registers[type1.intValue][type2.intValue].creator;
		if(creator != null){
			if( s_registers[type1.intValue][type2.intValue].primary){
				return creator.contactCreateFcn(fixtureA, fixtureB);
			}else{
				return creator.contactCreateFcn(fixtureB, fixtureA);
			}
		}else{
			return null;
		}
	}
	
	public static void destroy(Contact contact, ShapeType typeA, ShapeType typeB){
		// djm: what's here?
	}
	
	public static void destroy(Contact contact){
		assert(s_initialized == true);
		
		if(contact.m_manifold.pointCount > 0){
			contact.getFixtureA().getBody().setAwake(true);
			contact.getFixtureB().getBody().setAwake(true);
		}
		
		ShapeType type1 = contact.getFixtureA().getType();
		ShapeType type2 = contact.getFixtureB().getType();
		
		ContactCreator creator = s_registers[type1.intValue][type2.intValue].creator;
		creator.contactDestroyFcn(contact);
	}
	
	
	protected int m_flags;
	
	// World pool and list pointers.
	protected Contact m_prev;
	protected Contact m_next;

	// Nodes for connecting bodies.
	protected ContactEdge m_nodeA;
	protected ContactEdge m_nodeB;

	protected Fixture m_fixtureA;
	protected Fixture m_fixtureB;

	protected Manifold m_manifold;

	protected float m_toiCount;
	
	
	protected Contact(){
		m_fixtureA = null;
		m_fixtureB = null;
	}
	
	protected Contact(Fixture fA, Fixture fB){
		init(fA, fB);
	}
	
	
	/** initialization for pooling */
	public void init(Fixture fA, Fixture fB){
		m_flags = 0;
		

		m_fixtureA = fA;
		m_fixtureB = fB;

		m_manifold = new Manifold();
		m_manifold.pointCount = 0;

		m_prev = null;
		m_next = null;

		m_nodeA = new ContactEdge();
		m_nodeA.contact = null;
		m_nodeA.prev = null;
		m_nodeA.next = null;
		m_nodeA.other = null;

		m_nodeB = new ContactEdge();
		m_nodeB.contact = null;
		m_nodeB.prev = null;
		m_nodeB.next = null;
		m_nodeB.other = null;
		
		m_toiCount = 0;
	}
	
	
	/**
	 * Get the contact manifold. Do not set the point count to zero. Instead
	 * call Disable.
	 */
	public Manifold getManifold(){
		return m_manifold;
	}

	/**
	 * Get the world manifold.
	 */
	public void getWorldManifold(WorldManifold worldManifold){
		final Body bodyA = m_fixtureA.getBody();
		final Body bodyB = m_fixtureB.getBody();
		final Shape shapeA = m_fixtureA.getShape();
		final Shape shapeB = m_fixtureB.getShape();
		
		worldManifold.initialize(m_manifold, bodyA.getTransform(), shapeA.m_radius, bodyB.getTransform(), shapeB.m_radius);
	}
	
	/**
	 * Is this contact touching
	 * @return
	 */
	public boolean isTouching(){
		return (m_flags & TOUCHING_FLAG) == TOUCHING_FLAG;
	}
	
	/**
	 * Enable/disable this contact. This can be used inside the pre-solve
	 * contact listener. The contact is only disabled for the current
	 * time step (or sub-step in continuous collisions).
	 * @param flag
	 */
	public void setEnabled(boolean flag){
		if(flag){
			m_flags |= ENABLED_FLAG;
		}else{
			m_flags &= ~ENABLED_FLAG;
		}
	}

	/**
	 * Has this contact been disabled?
	 * @return
	 */
	public boolean isEnabled(){
		return (m_flags & ENABLED_FLAG) == ENABLED_FLAG;
	}

	/**
	 * Get the next contact in the world's contact list.
	 * @return
	 */
	public Contact getNext(){
		return m_next;
	}

	/**
	 * Get the first fixture in this contact.
	 * @return
	 */
	public Fixture getFixtureA(){
		return m_fixtureA;
	}

	/**
	 * Get the second fixture in this contact.
	 * @return
	 */
	public Fixture getFixtureB(){
		return m_fixtureB;
	}
	
	public abstract void evaluate(Manifold manifold, Transform xfA, Transform xfB);
	

	/**
	 * Flag this contact for filtering. Filtering will occur the next time step.
	 */
	public void flagForFiltering(){
		m_flags |= FILTER_FLAG;
	}
	
	// djm pooling
	private static final TLManifold tloldManifold = new TLManifold();
	
	protected void update(ContactListener listener){
		
		Manifold oldManifold = tloldManifold.get();
		oldManifold.set(m_manifold);
		
		// Re-enable this contact.
		m_flags |= ENABLED_FLAG;

		boolean touching = false;
		boolean wasTouching = (m_flags & TOUCHING_FLAG) == TOUCHING_FLAG;
		
		boolean sensorA = m_fixtureA.isSensor();
		boolean sensorB = m_fixtureB.isSensor();
		boolean sensor = sensorA || sensorB;

		Body bodyA = m_fixtureA.getBody();
		Body bodyB = m_fixtureB.getBody();
		Transform xfA = bodyA.getTransform();
		Transform xfB = bodyB.getTransform();
		
		if(sensor){
			Shape shapeA = m_fixtureA.getShape();
			Shape shapeB = m_fixtureB.getShape();
			touching = SingletonPool.getCollision().testOverlap(shapeA, shapeB, xfA, xfB);
			
			// Sensors don't generate manifolds.
			m_manifold.pointCount = 0;
		}
		else{
			evaluate(m_manifold, xfA, xfB);
			touching = m_manifold.pointCount > 0;
			
			// Match old contact ids to new contact ids and copy the
			// stored impulses to warm start the solver.
			for (int i = 0; i < m_manifold.pointCount; ++i){
				ManifoldPoint mp2 = m_manifold.points[i];
				mp2.normalImpulse = 0.0f;
				mp2.tangentImpulse = 0.0f;
				ContactID id2 = mp2.id;

				for (int j = 0; j < oldManifold.pointCount; ++j){
					ManifoldPoint mp1 = oldManifold.points[j];

					if (mp1.id.key == id2.key){
						mp2.normalImpulse = mp1.normalImpulse;
						mp2.tangentImpulse = mp1.tangentImpulse;
						break;
					}
				}
			}
			
			if(touching != wasTouching){
				bodyA.setAwake(true);
				bodyB.setAwake(true);
			}
		}

		if(touching){
			m_flags |= TOUCHING_FLAG;
		}else{
			m_flags &= ~TOUCHING_FLAG;
		}

		if(listener != null){
			return;
		}
		
		if (wasTouching == false && touching == true){
			listener.beginContact(this);
		}

		if (wasTouching == true && touching == false){
			listener.endContact(this);
		}

		if (sensor == false && touching){
			listener.preSolve(this, oldManifold);
		}
	}
	
	protected abstract void evaluate();

//	// djm pooled
//	private static final TLTOIInput tlinput = new TLTOIInput();
//	
//	protected float computeTOI(Sweep sweepA, Sweep sweepB){
//		TOIInput input = tlinput.get();
//		input.proxyA.set(m_fixtureA.getShape());
//		input.proxyB.set(m_fixtureB.getShape());
//		input.sweepA = sweepA;
//		input.sweepB = sweepB;
//		input.tolerance = Settings.linearSlop;
//
//		return SingletonPool.getTOI().timeOfImpact(input);
//	}
}
