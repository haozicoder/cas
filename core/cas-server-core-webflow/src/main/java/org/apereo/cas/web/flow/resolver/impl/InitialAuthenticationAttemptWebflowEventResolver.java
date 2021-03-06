package org.apereo.cas.web.flow.resolver.impl;

import com.google.common.collect.ImmutableSet;
import org.apereo.cas.CentralAuthenticationService;
import org.apereo.cas.authentication.AuthenticationException;
import org.apereo.cas.authentication.AuthenticationResultBuilder;
import org.apereo.cas.authentication.AuthenticationSystemSupport;
import org.apereo.cas.authentication.Credential;
import org.apereo.cas.authentication.principal.Service;
import org.apereo.cas.services.MultifactorAuthenticationProviderSelector;
import org.apereo.cas.services.RegisteredService;
import org.apereo.cas.services.RegisteredServiceAccessStrategyUtils;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.ticket.AbstractTicketException;
import org.apereo.cas.ticket.registry.TicketRegistrySupport;
import org.apereo.cas.validation.AuthenticationRequestServiceSelectionStrategy;
import org.apereo.cas.web.flow.CasWebflowConstants;
import org.apereo.cas.web.flow.resolver.CasDelegatingWebflowEventResolver;
import org.apereo.cas.web.flow.resolver.CasWebflowEventResolver;
import org.apereo.cas.web.support.WebUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.util.CookieGenerator;
import org.springframework.webflow.execution.Event;
import org.springframework.webflow.execution.RequestContext;

import javax.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This is {@link InitialAuthenticationAttemptWebflowEventResolver},
 * which handles the initial authentication attempt and calls upon a number of
 * embedded resolvers to produce the next event in the authentication flow.
 *
 * @author Misagh Moayyed
 * @since 5.0.0
 */
public class InitialAuthenticationAttemptWebflowEventResolver extends AbstractCasWebflowEventResolver
        implements CasDelegatingWebflowEventResolver {

    private final List<CasWebflowEventResolver> orderedResolvers = new ArrayList<>();

    private CasWebflowEventResolver selectiveResolver;

    public InitialAuthenticationAttemptWebflowEventResolver(final AuthenticationSystemSupport authenticationSystemSupport,
                                                            final CentralAuthenticationService centralAuthenticationService,
                                                            final ServicesManager servicesManager, final TicketRegistrySupport ticketRegistrySupport,
                                                            final CookieGenerator warnCookieGenerator,
                                                            final List<AuthenticationRequestServiceSelectionStrategy> authenticationSelectionStrategies,
                                                            final MultifactorAuthenticationProviderSelector selector) {
        super(authenticationSystemSupport, centralAuthenticationService, servicesManager, ticketRegistrySupport, warnCookieGenerator,
                authenticationSelectionStrategies, selector);
    }

    @Override
    public Set<Event> resolveInternal(final RequestContext context) {
        try {
            final Credential credential = getCredentialFromContext(context);
            if (credential != null) {
                final AuthenticationResultBuilder builder =
                        this.authenticationSystemSupport.handleInitialAuthenticationTransaction(credential);

                if (builder.getInitialAuthentication().isPresent()) {
                    WebUtils.putAuthenticationResultBuilder(builder, context);
                    WebUtils.putAuthentication(builder.getInitialAuthentication().get(), context);
                }
            }
            final Service service = WebUtils.getService(context);
            if (service != null) {

                logger.debug("Locating service {} in service registry to determine authentication policy", service);
                final RegisteredService registeredService = this.servicesManager.findServiceBy(service);
                RegisteredServiceAccessStrategyUtils.ensureServiceAccessIsAllowed(service, registeredService);

                final Set<Event> resolvedEvents = resolveCandidateAuthenticationEvents(context, service, registeredService);
                if (!resolvedEvents.isEmpty()) {
                    putResolvedEventsAsAttribute(context, resolvedEvents);
                    final Event finalResolvedEvent = this.selectiveResolver.resolveSingle(context);
                    if (finalResolvedEvent != null) {
                        return ImmutableSet.of(finalResolvedEvent);
                    }
                }
            }

            final AuthenticationResultBuilder builder = WebUtils.getAuthenticationResultBuilder(context);
            if (builder == null) {
                throw new IllegalArgumentException("No authentication result builder can be located in the context");
            }
            return ImmutableSet.of(grantTicketGrantingTicketToAuthenticationResult(context, builder, service));
        } catch (final Exception e) {
            Event event = returnAuthenticationExceptionEventIfNeeded(e);
            if (event == null) {
                logger.warn(e.getMessage(), e);
                event = newEvent(CasWebflowConstants.TRANSITION_ID_ERROR, e);
            }
            final HttpServletResponse response = WebUtils.getHttpServletResponse(context);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return ImmutableSet.of(event);
        }
    }

    /**
     * Resolve candidate authentication events set.
     *
     * @param context           the context
     * @param service           the service
     * @param registeredService the registered service
     * @return the set
     */
    protected Set<Event> resolveCandidateAuthenticationEvents(final RequestContext context, final Service service, final RegisteredService registeredService) {
        return this.orderedResolvers.stream()
                .filter(Objects::nonNull)
                .map(resolver -> resolver.resolveSingle(context))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    @Override
    public void addDelegate(final CasWebflowEventResolver r) {
        orderedResolvers.add(r);
    }

    @Override
    public void addDelegate(final CasWebflowEventResolver r, final int index) {
        orderedResolvers.add(index, r);
    }

    public void setSelectiveResolver(final CasWebflowEventResolver r) {
        this.selectiveResolver = r;
    }

    private Event returnAuthenticationExceptionEventIfNeeded(final Exception e) {
        final Exception ex;
        if (e instanceof AuthenticationException || e instanceof AbstractTicketException) {
            ex = e;
        } else if (e.getCause() instanceof AuthenticationException || e.getCause() instanceof AbstractTicketException) {
            ex = (Exception) e.getCause();
        } else {
            return null;
        }

        logger.debug(ex.getMessage(), ex);
        return newEvent(CasWebflowConstants.TRANSITION_ID_AUTHENTICATION_FAILURE, ex);
    }
}
