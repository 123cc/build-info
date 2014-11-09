package org.jfrog.build.extractor.maven.resolver;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.internal.impl.DefaultMetadataResolver;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.MetadataRequest;
import org.eclipse.aether.resolution.MetadataResult;

import javax.inject.Named;
import java.util.Collection;
import java.util.List;

@Named
@Component( role = ArtifactoryEclipseMetadataResolver.class )
public class ArtifactoryEclipseMetadataResolver extends DefaultMetadataResolver {

    @Requirement
    private ResolutionHelper resolutionHelper;

    @Requirement
    private Logger logger;

    @Requirement
    private ArtifactoryEclipseResolversHelper helper;

    private void enforceResolutionRepositories(RepositorySystemSession session, MetadataRequest request) {
        // Get the Artifactory repositories configured in the Artifactory plugin:
        List<RemoteRepository> repositories = helper.getResolutionRepositories(session);

        // The repositories list can be empty, in case this build is not running from a CI server.
        // In that case, we do not want to override Maven's configured repositories:
        if (repositories != null && !repositories.isEmpty() && request.getRepository() != null && request.getMetadata() != null) {
            if (request.getMetadata().getNature() == Metadata.Nature.SNAPSHOT) {
                request.setRepository(getSnapshotRepository(session));
            } else {
                request.setRepository(getReleaseRepository(session));
            }
        }
    }

    @Override
    public List<MetadataResult> resolveMetadata(RepositorySystemSession session, Collection<? extends MetadataRequest> requests ) {
        for(MetadataRequest request : requests) {
            enforceResolutionRepositories(session, request);
        }
        // Now we let Maven resolve the artifacts:
        return super.resolveMetadata(session, requests);
    }

    public RemoteRepository getSnapshotRepository(RepositorySystemSession session) {
        return helper.getSnapshotRepository(session);
    }

    public RemoteRepository getReleaseRepository(RepositorySystemSession session) {
        return helper.getReleaseRepository(session);
    }
}
