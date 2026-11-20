//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.annotations;

import java.util.ArrayList;
import java.util.List;
import javax.servlet.ServletSecurityElement;
import javax.servlet.annotation.ServletSecurity;
import javax.servlet.annotation.ServletSecurity.EmptyRoleSemantic;
import javax.servlet.annotation.ServletSecurity.TransportGuarantee;

import org.eclipse.jetty.annotations.AnnotationIntrospector.AbstractIntrospectableAnnotationHandler;
import org.eclipse.jetty.security.ConstraintAware;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * ServletSecurityAnnotationHandler
 *
 * Inspect a class to see if it has an <code>&#064;ServletSecurity</code> annotation on it,
 * setting up the <code>&lt;security-constraint&gt;s</code>.
 *
 * A servlet can be defined in:
 * <ul>
 * <li>web.xml</li>
 * <li>web-fragment.xml</li>
 * <li>@WebServlet annotation discovered</li>
 * <li>ServletContext.createServlet</li>
 * </ul>
 *
 * The ServletSecurity annotation for a servlet should only be processed
 * iff metadata-complete == false.
 */
public class ServletSecurityAnnotationHandler extends AbstractIntrospectableAnnotationHandler
{
    private static final Logger LOG = Log.getLogger(ServletSecurityAnnotationHandler.class);

    private WebAppContext _context;

    public ServletSecurityAnnotationHandler(WebAppContext wac)
    {
        super(false);
        _context = wac;
    }

    /**
     * @see org.eclipse.jetty.annotations.AnnotationIntrospector.IntrospectableAnnotationHandler#handle(java.lang.Class)
     */
    @Override
    public void doHandle(Class clazz)
    {
        if (!(_context.getSecurityHandler() instanceof ConstraintAware))
        {
            LOG.warn("SecurityHandler not ConstraintAware, skipping security annotation processing");
            return;
        }

        ServletSecurity servletSecurity = (ServletSecurity)clazz.getAnnotation(ServletSecurity.class);
        if (servletSecurity == null)
            return;

        List<ServletMapping> servletMappings = getServletMappings(clazz.getCanonicalName());
        List<ConstraintMapping> existingMappings = ((ConstraintAware)_context.getSecurityHandler()).getConstraintMappings();

        //Make a fresh list
        List<ConstraintMapping> constraintMappings = new ArrayList<ConstraintMapping>();

        ServletSecurityElement securityElement = new ServletSecurityElement(servletSecurity);
        for (ServletMapping sm : servletMappings)
        {
            for (String url : sm.getPathSpecs())
            {
                // A descriptor or programmatic constraint takes precedence only for its own URL pattern.
                if (constraintExists(url, existingMappings))
                    continue;

                _context.getMetaData().setOrigin("constraint.url." + url, servletSecurity, clazz);
                constraintMappings.addAll(ConstraintSecurityHandler.createConstraintsWithMappingsForPath(clazz.getName(), url, securityElement));
            }
        }

        //set up the security constraints produced by the annotation
        ConstraintAware securityHandler = (ConstraintAware)_context.getSecurityHandler();

        for (ConstraintMapping m : constraintMappings)
        {
            securityHandler.addConstraintMapping(m);
        }

        //Servlet Spec 3.1 requires paths with uncovered http methods to be reported
        securityHandler.checkPathsWithUncoveredHttpMethods();
    }

    /**
     * Make a jetty Constraint object, which represents the <code>&lt;auth-constraint&gt;</code> and
     * <code>&lt;user-data-constraint&gt;</code> elements, based on the security annotation.
     *
     * @param servlet the servlet
     * @param rolesAllowed the roles allowed
     * @param permitOrDeny the role / permission semantic
     * @param transport the transport guarantee
     * @return the constraint
     */
    protected Constraint makeConstraint(Class servlet, String[] rolesAllowed, EmptyRoleSemantic permitOrDeny, TransportGuarantee transport)
    {
        return ConstraintSecurityHandler.createConstraint(servlet.getName(), rolesAllowed, permitOrDeny, transport);
    }

    /**
     * Get the ServletMappings for the servlet's class.
     *
     * @param className the class name
     * @return the servlet mappings for the class
     */
    protected List<ServletMapping> getServletMappings(String className)
    {
        List<ServletMapping> results = new ArrayList<ServletMapping>();
        ServletMapping[] mappings = _context.getServletHandler().getServletMappings();
        for (ServletMapping mapping : mappings)
        {
            //Check the name of the servlet that this mapping applies to, and then find the ServletHolder for it to find it's class
            ServletHolder holder = _context.getServletHandler().getServlet(mapping.getServletName());
            if (holder.getClassName() != null && holder.getClassName().equals(className))
                results.add(mapping);
        }
        return results;
    }

    /**
     * Check whether a constraint already exists for this URL pattern.
     *
     * @param pathSpec the servlet URL pattern
     * @param constraintMappings the constraint mappings
     * @return true if constraint exists
     */
    protected boolean constraintExists(String pathSpec, List<ConstraintMapping> constraintMappings)
    {
        if (constraintMappings != null)
        {
            for (ConstraintMapping mapping : constraintMappings)
            {
                if (pathSpec.equals(mapping.getPathSpec()))
                    return true;
            }
        }
        return false;
    }

    /**
     * Retained for subclasses that check whether any servlet mapping has a constraint.
     *
     * @param servletMappings the servlet mappings
     * @param constraintMappings the constraint mappings
     * @return true if any servlet URL pattern has a constraint
     */
    protected boolean constraintsExist(List<ServletMapping> servletMappings, List<ConstraintMapping> constraintMappings)
    {
        for (ServletMapping mapping : servletMappings)
        {
            String[] pathSpecs = mapping.getPathSpecs();
            if (pathSpecs == null)
                continue;
            for (String pathSpec : pathSpecs)
            {
                if (constraintExists(pathSpec, constraintMappings))
                    return true;
            }
        }
        return false;
    }
}
