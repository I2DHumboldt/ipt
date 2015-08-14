package org.gbif.ipt.model;

import org.gbif.api.model.common.DOI;
import org.gbif.dwc.terms.Term;
import org.gbif.dwc.terms.TermFactory;
import org.gbif.ipt.config.Constants;
import org.gbif.ipt.model.voc.IdentifierStatus;
import org.gbif.ipt.model.voc.PublicationMode;
import org.gbif.ipt.model.voc.PublicationStatus;
import org.gbif.ipt.service.AlreadyExistingException;
import org.gbif.metadata.eml.Agent;
import org.gbif.metadata.eml.Citation;
import org.gbif.metadata.eml.Eml;
import org.gbif.metadata.eml.MaintenanceUpdateFrequency;

import java.io.Serializable;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import javax.validation.constraints.NotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.sun.tools.internal.jxc.ap.Const;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

import static com.google.common.base.Objects.equal;

/**
 * The main class to represent an IPT resource.
 * Its enumerated type property defines the kind of resource (Metadata, Checklist, Occurrence)
 * A resource can be identified by its short name which has to be unique within an IPT instance.
 */
public class Resource implements Serializable, Comparable<Resource> {

  public enum CoreRowType {
    OCCURRENCE, CHECKLIST, SAMPLINGEVENT, METADATA, OTHER
  }

  private static Logger log = Logger.getLogger(Resource.class);

  private static final TermFactory TERM_FACTORY = TermFactory.instance();

  private static final long serialVersionUID = 3832626162173352190L;
  private String shortname; // unique
  private Eml eml = new Eml();
  private String coreType;
  private String subtype;
  // update frequency
  private MaintenanceUpdateFrequency updateFrequency;
  // publication status
  private PublicationStatus status = PublicationStatus.PRIVATE;
  // publication mode
  private PublicationMode publicationMode;
  // is resource citation to be auto-generated?
  private boolean citationAutoGenerated;
  // resource version and eml version are the same
  private BigDecimal emlVersion;
  // resource version replaced
  private BigDecimal replacedEmlVersion;
  // last time resource was successfully published
  private Date lastPublished;
  // next time resource is scheduled to be pubished
  private Date nextPublished;
  private int recordsPublished = 0;
  // registry data - only exists when status=REGISTERED
  private UUID key;
  private Organisation organisation;
  // resource meta-metadata
  private User creator;
  private Date created;
  private User modifier;
  private Date modified;
  private Date metadataModified;
  private Date mappingsModified;
  private Date sourcesModified;
  private Set<User> managers = new HashSet<User>();
  // mapping configs
  private Set<Source> sources = new HashSet<Source>();
  private List<ExtensionMapping> mappings = Lists.newArrayList();

  private String changeSummary;
  private List<VersionHistory> versionHistory = Lists.newLinkedList();

  private IdentifierStatus identifierStatus = IdentifierStatus.UNRESERVED;
  private DOI doi;
  private UUID doiOrganisationKey;

  public void addManager(User manager) {
    if (manager != null) {
      this.managers.add(manager);
    }
  }

  /**
   * Add new VersionHistory, as long as a VersionHistory with same version hasn't been added yet. The new version
   * gets added at the top of the list.
   *
   * @param history VersionHistory to add
   */
  public void addVersionHistory(VersionHistory history) {
    Preconditions.checkNotNull(history);
    if (versionHistory == null) {
      versionHistory = Lists.newLinkedList();
    }
    boolean exists = false;
    for (VersionHistory vh : versionHistory) {
      if (vh.getVersion().compareTo(history.getVersion()) == 0) {
        exists = true;
      }
    }
    if (!exists) {
      versionHistory.add(0, history);
    }
  }

  /**
   * Remove a VersionHistory with specific version.
   *
   * @param version version of VersionHistory to remove
   */
  public void removeVersionHistory(BigDecimal version) {
    if (versionHistory == null) {
      versionHistory = Lists.newArrayList();
    }
    if (version != null) {
      Iterator<VersionHistory> iter = versionHistory.iterator();
      while (iter.hasNext()) {
        BigDecimal historyVersion = new BigDecimal(iter.next().getVersion());
        if (version.compareTo(historyVersion) == 0) {
          iter.remove();
        }
      }
    }
  }

  /**
   * Find and return a VersionHistory with specific version.
   *
   * @param version version of VersionHistory searched for
   *
   * @return VersionHistory with specific version or null if not found
   */
  public VersionHistory findVersionHistory(BigDecimal version) {
    if (version != null) {
      for (VersionHistory vh : versionHistory) {
        if (version.compareTo(new BigDecimal(vh.getVersion())) == 0) {
          return vh;
        }
      }
    }
    return null;
  }

  /**
   * Adds a new extension mapping to the resource. For non core extensions a core extension must exist already.
   * It returns the list index for this mapping according to getMappings(rowType)
   *
   * @return list index corresponding to getMappings(rowType) or null if the mapping couldnt be added
   *
   * @throws IllegalArgumentException if no core mapping exists when adding a non core mapping
   */
  public Integer addMapping(ExtensionMapping mapping) throws IllegalArgumentException {
    if (mapping != null && mapping.getExtension() != null) {
      if (!mapping.isCore() && !hasCore()) {
        throw new IllegalArgumentException("Cannot add extension mapping before a core mapping exists");
      }
      Integer index = getMappings(mapping.getExtension().getRowType()).size();
      this.mappings.add(mapping);
      return index;
    }
    return null;
  }

  public void addSource(Source src, boolean allowOverwrite) throws AlreadyExistingException {
    // make sure we talk about the same resource
    src.setResource(this);
    if (!allowOverwrite && sources.contains(src)) {
      throw new AlreadyExistingException();
    }
    if (allowOverwrite && sources.contains(src)) {
      // If source file is going to be overwritten, it should be actually re-add it.
      sources.remove(src);
      // Changing the SourceBase in the ExtensionMapping object from the mapping list.
      for (ExtensionMapping ext : this.getMappings()) {
        if (ext.getSource().equals(src)) {
          ext.setSource(src);
        }
      }
    }
    sources.add(src);
  }

  /*
   * (non-Javadoc)
   * @see java.lang.Comparable#compareTo(java.lang.Object)
   */
  public int compareTo(Resource o) {
    return shortname.compareToIgnoreCase(o.shortname);
  }

  /**
   * Delete a Resource's mapping. If the mapping gets successfully deleted, and the mapping is a core type mapping,
   * and there are no additional core type mappings, all other mappings are also cleared.
   *
   * @param mapping ExtensionMapping
   *
   * @return if deletion was successful or not
   */
  public boolean deleteMapping(ExtensionMapping mapping) {
    boolean result = false;
    if (mapping != null) {
      // what's the core row type? store it before deleting the mapping, just in case this is the last core mapping!
      String coreRowType = getCoreRowType();
      result = mappings.remove(mapping);
      if (result && mapping.isCore() && getCoreMappings(coreRowType).isEmpty()) {
        mappings.clear();
      }
    }
    return result;
  }

  public boolean deleteSource(Source src) {
    boolean result = false;
    if (src != null) {
      result = sources.remove(src);
      // also remove existing mappings
      List<ExtensionMapping> ems = new ArrayList<ExtensionMapping>(mappings);
      for (ExtensionMapping em : ems) {
        if (em.getSource() != null && src.equals(em.getSource())) {
          deleteMapping(em);
          log.debug("Cascading source delete to mapping " + em.getExtension().getTitle());
        }
      }
    }
    return result;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Resource)) {
      return false;
    }
    Resource o = (Resource) other;
    return equal(shortname, o.shortname);
  }

  /**
   * @return all core mappings, excluding extension mappings with core row types
   */
  public List<ExtensionMapping> getCoreMappings() {
    List<ExtensionMapping> cores = new ArrayList<ExtensionMapping>();
    String coreRowType = getCoreRowType();
    for (ExtensionMapping m : mappings) {
      if (m.isCore() && coreRowType != null && coreRowType.equalsIgnoreCase(m.getExtension().getRowType())) {
        cores.add(m);
      }
    }
    return cores;
  }

  /**
   * @param coreRowType core rowType
   *
   * @return all core mappings, excluding extension mappings with core row types
   */
  public List<ExtensionMapping> getCoreMappings(String coreRowType) {
    List<ExtensionMapping> cores = new ArrayList<ExtensionMapping>();
    for (ExtensionMapping m : mappings) {
      if (m.isCore() && coreRowType != null && coreRowType.equalsIgnoreCase(m.getExtension().getRowType())) {
        cores.add(m);
      }
    }
    return cores;
  }

  /**
   * @return the row type of the first core extension mapping, which always determines the core row type
   */
  public String getCoreRowType() {
    for (ExtensionMapping m : mappings) {
      if (m.isCore()) {
        return m.getExtension().getRowType();
      }
    }
    return null;
  }

  /**
   * At first the core type can be set during resource creation or on the basic metadata page. But once
   * a core mapping has been done, it is derived from the core mapping.
   *
   * @return the core type.
   */
  @Nullable
  public String getCoreType() {
    String coreRowType = getCoreRowType();
    if (coreRowType != null) {
      if (coreRowType.equalsIgnoreCase(Constants.DWC_ROWTYPE_TAXON)) {
        coreType = StringUtils.capitalize(CoreRowType.CHECKLIST.toString());
      } else if (coreRowType.equalsIgnoreCase(Constants.DWC_ROWTYPE_OCCURRENCE)) {
        coreType = StringUtils.capitalize(CoreRowType.OCCURRENCE.toString());
      } else if (coreRowType.equalsIgnoreCase(Constants.DWC_ROWTYPE_EVENT)) {
        coreType = StringUtils.capitalize(CoreRowType.SAMPLINGEVENT.toString());
      } else {
        coreType = StringUtils.capitalize(CoreRowType.OTHER.toString());
      }
    }
    return coreType;
  }

  public Term getCoreTypeTerm() {
    List<ExtensionMapping> cores = getCoreMappings();
    if (!cores.isEmpty()) {
      return TERM_FACTORY.findTerm(cores.get(0).getExtension().getRowType());
    }
    return null;
  }

  public Date getCreated() {
    return created;
  }

  public User getCreator() {
    return creator;
  }

  public Eml getEml() {
    return eml;
  }

  /**
   * Get resource version. Same as EML version.
   *
   * @return resource version
   */
  @NotNull
  public BigDecimal getEmlVersion() {
    return (emlVersion == null) ? eml.getEmlVersion() : emlVersion;
  }

  /**
   * Get the next resource version. If the resource has never been published, the next resource version
   * is 1.0. If no new DOI has been reserved for the resource, the version is bumped by a minor resource version.
   * If a new DOI has been reserved for the resource, and the resource's visibility is public, the version is bumped by
   * a major resource version.
   *
   * @return next resource version
   */
  @NotNull
  public BigDecimal getNextVersion() {
    // first publication retrieve existing version
    if (lastPublished == null) {
      return getEml().getEmlVersion();
    }
    // There are two cases that warrant a new major version, provided a doi has been reserved for resource
    // #1: no DOI has been assigned yet, and resource's visibility is public (or registered)
    // #2: a DOI has been assigned already
    if (doi != null && identifierStatus == IdentifierStatus.PUBLIC_PENDING_PUBLICATION) {
      if (!isAlreadyAssignedDoi() && (status == PublicationStatus.PUBLIC || status == PublicationStatus.REGISTERED)) {
        return getEml().getNextEmlVersionAfterMajorVersionChange();
      } else if (isAlreadyAssignedDoi()) {
        return getEml().getNextEmlVersionAfterMajorVersionChange();
      }
    }
    // all other cases warrant a minor version increment
    return getEml().getNextEmlVersionAfterMinorVersionChange();
  }

  /**
   * @return true if the resource has already been assigned a DOI, false otherwise. Remember only DOIs that are public
   * have officially been assigned/registered.
   */
  public boolean isAlreadyAssignedDoi() {
    if (!getVersionHistory().isEmpty()) {
      DOI doi = getVersionHistory().get(0).getDoi();
      IdentifierStatus status = getVersionHistory().get(0).getStatus();
      if (doi != null && status == IdentifierStatus.PUBLIC) {
        return true;
      }
    }
    return false;
  }

  /**
   * @return the DOI assigned/registered to the last published public version.
   */
  @Nullable
  public DOI getAssignedDoi() {
    if (isAlreadyAssignedDoi()) {
      DOI doi = getVersionHistory().get(0).getDoi();
      IdentifierStatus status = getVersionHistory().get(0).getStatus();
      if (doi != null && status == IdentifierStatus.PUBLIC) {
        return doi;
      }
    }
    return null;
  }

  /**
   * @return version number of last published resource, or null if last published version had none
   */
  public BigDecimal getLastPublishedVersionsVersion() {
    if (!getVersionHistory().isEmpty()) {
      return new BigDecimal(getVersionHistory().get(0).getVersion());
    }
    return null;
  }

  /**
   * @return change summary of last published resource, or null if last published version had none
   */
  public String getLastPublishedVersionsChangeSummary() {
    if (!getVersionHistory().isEmpty()) {
      System.out.print(Strings.emptyToNull(getVersionHistory().get(0).getChangeSummary()));
      return Strings.emptyToNull(getVersionHistory().get(0).getChangeSummary());
    }
    return null;
  }

  public UUID getKey() {
    return key;
  }

  /**
   * Return the date the resource was last published successfully.
   *
   * @return the date the resource was last published successfully
   */
  public Date getLastPublished() {
    return lastPublished;
  }

  /**
   * Return the date the resource is scheduled to be published next.
   *
   * @return the date the resource is scheduled to be published next.
   */
  public Date getNextPublished() {
    return nextPublished;
  }

  public Set<User> getManagers() {
    return managers;
  }


  /**
   * @return a list of extensions that have been mapped to, starting with the extension that was mapped first (core
   * mapping), and ending with the extension that was mapped last. Elements in the list are unique.
   */
  public List<Extension> getMappedExtensions() {
    LinkedHashSet<Extension> extensions = Sets.newLinkedHashSet();
    for (ExtensionMapping em : mappings) {
      if (em.getExtension() != null && em.getSource() != null) {
        extensions.add(em.getExtension());
      } else {
        log.error("ExtensionMapping referencing NULL Extension or Source for resource: " + getShortname());
      }
    }
    return Lists.newArrayList(extensions);
  }

  public ExtensionMapping getMapping(String rowType, Integer index) {
    if (rowType != null && index != null) {
      List<ExtensionMapping> maps = getMappings(rowType);
      if (maps.size() >= index) {
        return maps.get(index);
      }
    }
    return null;
  }

  public List<ExtensionMapping> getMappings() {
    return mappings;
  }

  /**
   * Get the list of mappings for the requested extension rowtype.
   * The order of mappings in the list is guaranteed to be stable and the same as the underlying original mappings
   * list.
   *
   * @param rowType identifying the extension
   *
   * @return the list of mappings for the requested extension rowtype
   */
  public List<ExtensionMapping> getMappings(String rowType) {
    List<ExtensionMapping> maps = new ArrayList<ExtensionMapping>();
    if (rowType != null) {
      for (ExtensionMapping m : mappings) {
        if (rowType.equals(m.getExtension().getRowType())) {
          maps.add(m);
        }
      }
    }
    return maps;
  }

  public Date getModified() {
    return modified;
  }

  public User getModifier() {
    return modifier;
  }

  public Organisation getOrganisation() {
    return organisation;
  }

  public int getRecordsPublished() {
    return recordsPublished;
  }

  public String getShortname() {
    return shortname;
  }

  public Source getSource(String name) {
    if (name == null) {
      return null;
    }
    name = SourceBase.normaliseName(name);
    for (Source s : sources) {
      if (s.getName().equals(name)) {
        return s;
      }
    }
    return null;
  }

  public List<Source> getSources() {
    return Ordering.natural().nullsLast().onResultOf(new Function<Source, String>() {
      @Nullable
      public String apply(@Nullable Source src) {
        return src.getName();
      }
    }).sortedCopy(sources);
  }

  @NotNull
  public PublicationStatus getStatus() {
    return status;
  }

  /**
   * @return the status of the DOI
   */
  @NotNull
  public IdentifierStatus getIdentifierStatus() {
    return identifierStatus;
  }

  /**
   * @param identifierStatus the status of the DOI (should be paired with resource.doi)
   */
  public void setIdentifierStatus(@NotNull IdentifierStatus identifierStatus) {
    this.identifierStatus = identifierStatus;
  }

  /**
   * @return the DOI of the resource, always in prefix/suffix format excluding "doi:", e.g. 10.1234/qu83ng
   */
  @Nullable
  public DOI getDoi() {
    return doi;
  }

  /**
   * @param doi the DOI of the resource (should be paired with resource.identifierStatus)
   */
  public void setDoi(@Nullable DOI doi) {
    this.doi = doi;
  }

  /**
   * @return the key of the organisation that assigned the DOI to the resource
   */
  @Nullable
  public UUID getDoiOrganisationKey() {
    return doiOrganisationKey;
  }

  /**
   * @param doiOrganisationKey the key of the organisation that assigned the DOI to the resource
   */
  public void setDoiOrganisationKey(@Nullable UUID doiOrganisationKey) {
    this.doiOrganisationKey = doiOrganisationKey;
  }

  /**
   * Return the PublicationMode of the resource. Default is PublicationMode.AUTO_PUBLISH_OFF meaning that the
   * resource must be republished manually, and that the resource has not been configured yet for auto-publishing.
   *
   * @return the PublicationMode of the resource, or PublicationMode.AUTO_PUBLISH_OFF if not set yet
   */
  public PublicationMode getPublicationMode() {
    return (publicationMode == null) ? PublicationMode.AUTO_PUBLISH_OFF : publicationMode;
  }

  public String getSubtype() {
    return subtype;
  }

  /**
   * Return the frequency with which changes and additions are made to the dataset after the initial dataset is
   * completed.
   *
   * @return the maintenance update frequency
   */
  @Nullable
  public MaintenanceUpdateFrequency getUpdateFrequency() {
    return updateFrequency;
  }

  public String getTitle() {
    if (eml != null) {
      return eml.getTitle();
    }
    return null;
  }

  /**
   * Build and return string composed of resource title and shortname in brackets if the title and shortname are
   * different. This string can be called to construct log messages.
   *
   * @return constructed string
   */
  public String getTitleAndShortname() {
    StringBuilder sb = new StringBuilder();
    if (eml != null) {
      sb.append(eml.getTitle());
      if (!shortname.equalsIgnoreCase(eml.getTitle())) {
        sb.append(" (").append(shortname).append(")");
      }
    }
    return sb.toString();
  }

  /**
   * @return true if this resource is mapped to at least one core extension
   */
  public boolean hasCore() {
    return getCoreTypeTerm() != null;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(shortname);
  }

  public boolean hasMappedData() {
    for (ExtensionMapping cm : getCoreMappings()) {
      // test each core mapping if there is at least one field mapped
      if (!cm.getFields().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  public boolean hasPublishedData() {
    return recordsPublished > 0;
  }

  public boolean isPublished() {
    return lastPublished != null;
  }

  /**
   * @return true if the last published version is public, false otherwise
   */
  public boolean isLastPublishedVersionPublic() {
    List<VersionHistory> history = getVersionHistory();
    if (!history.isEmpty()) {
      VersionHistory latestVersion = history.get(0);
      if (latestVersion.getPublicationStatus().equals(PublicationStatus.PUBLIC)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Used before publishing a new version.
   *
   * @return true if the resource has been assigned a GBIF-supported license, false otherwise
   */
  public boolean isAssignedGBIFSupportedLicense() {
    return eml.parseLicenseUrl() != null && Constants.GBIF_SUPPORTED_LICENSES.contains(eml.parseLicenseUrl());
  }

  /**
   * @return publication status of last published version, defaulting to status=private if it is not definitively known
   */
  @NotNull
  public PublicationStatus getLastPublishedVersionsPublicationStatus() {
    List<VersionHistory> history = getVersionHistory();
    if (!history.isEmpty()) {
      return history.get(0).getPublicationStatus();
    } else if (status.equals(PublicationStatus.REGISTERED)) {
      return PublicationStatus.REGISTERED;
    } else {
      return PublicationStatus.PRIVATE;
    }
  }

  public boolean isRegistered() {
    return key != null && status.equals(PublicationStatus.REGISTERED);
  }

  public void setCoreType(String coreType) {
    this.coreType = Strings.isNullOrEmpty(coreType) ? null : coreType;
  }

  public void setCreated(Date created) {
    this.created = created;
    if (modified == null) {
      modified = created;
    }
  }

  public void setCreator(User creator) {
    this.creator = creator;
    if (modifier == null) {
      modifier = creator;
    }
  }

  public void setEml(Eml eml) {
    this.eml = eml;
  }

  /**
   * Set the new eml (resource) version. If the new version is greater than the existing version, the previous version
   * is stored.
   * </br>
   * Please note that comparison on the minor version number must include trailing zeros, e.g. 1.10 > 1.9
   *
   * @param v new eml (resource) version
   */
  public void setEmlVersion(BigDecimal v) {
    if (v != null && emlVersion != null) {
      int scale = v.scale(); // 0.10 has a scale of 2
      BigDecimal scaled = v.scaleByPowerOfTen(scale); // 0.10 * 10(2) = 10

      int scale2 = emlVersion.scale(); // 0.9 has a scale of 1
      BigDecimal scaled2 = emlVersion.scaleByPowerOfTen(scale2); // 0.9 * 10(1) = 9

      if (scaled.compareTo(scaled2) > 0) { // 10 > 9
        setReplacedEmlVersion(new BigDecimal(emlVersion.toPlainString()));
      }
    }
    emlVersion = v;
    if (eml != null) {
      eml.setEmlVersion(v);
    }
  }

  public void setKey(UUID key) {
    this.key = key;
  }

  public void setLastPublished(Date lastPublished) {
    this.lastPublished = lastPublished;
  }

  public void setNextPublished(Date nextPublished) {
    this.nextPublished = nextPublished;
  }

  public void setManagers(Set<User> managers) {
    this.managers = managers;
  }

  public void setMappings(List<ExtensionMapping> extensions) {
    this.mappings = extensions;
  }

  public void setModified(Date modified) {
    this.modified = modified;
  }

  public void setModifier(User modifier) {
    this.modifier = modifier;
  }

  public void setOrganisation(Organisation organisation) {
    this.organisation = organisation;
  }

  public void setRecordsPublished(int recordsPublished) {
    this.recordsPublished = recordsPublished;
  }

  public void setShortname(String shortname) {
    this.shortname = shortname;
    if (eml != null && eml.getTitle() == null) {
      eml.setTitle(shortname);
    }
  }

  public void setStatus(PublicationStatus status) {
    this.status = status;
  }

  /**
   * Sets the resource PublicationMode. Its value must come from the Enumeration PublicationMode.
   *
   * @param publicationMode PublicationMode
   */
  public void setPublicationMode(PublicationMode publicationMode) {
    this.publicationMode = publicationMode;
  }

  /**
   * Sets the resource subtype. If it is null or an empty string, it is set to null. Otherwise, it is simply set
   * in lowercase.
   *
   * @param subtype subtype String
   */
  public void setSubtype(String subtype) {
    this.subtype = (Strings.isNullOrEmpty(subtype)) ? null : subtype.toLowerCase();
  }

  /**
   * Sets the maintenance update frequency. Its value comes in as a String, and gets matched to the Enumeration
   * MainUpFreqType. If no match occurs, the value is set to null.
   *
   * @param updateFrequency MainUpFreqType Enum
   */
  public void setUpdateFrequency(String updateFrequency) {
    this.updateFrequency = MaintenanceUpdateFrequency.findByIdentifier(updateFrequency);
  }

  public void setTitle(String title) {
    if (eml != null) {
      this.eml.setTitle(title);
    }
  }

  @Override
  public String toString() {
    return "Resource " + shortname;
  }

  /**
   * Check if the resource has been configured for auto-publishing. To qualify, the resource must have an update
   * frequency suitable for auto-publishing (annually, biannually, monthly, weekly, daily) or have a next published
   * date that isn't null, and must have auto-publishing mode turned on.
   *
   * @return true if the resource uses auto-publishing
   */
  public boolean usesAutoPublishing() {
    return publicationMode == PublicationMode.AUTO_PUBLISH_ON && updateFrequency != null;
  }

  /**
   * @return the change summary, explaining what has changed in this version compared with the last
   */
  public String getChangeSummary() {
    return changeSummary;
  }

  /**
   * @param changeSummary the change summary, explaining what has changed in this version compared with the last
   */
  public void setChangeSummary(String changeSummary) {
    this.changeSummary = changeSummary;
  }

  /**
   * @return the version history
   */
  public List<VersionHistory> getVersionHistory() {
    if (versionHistory == null) {
      return Lists.newLinkedList();
    }
    return versionHistory;
  }

  public void setVersionHistory(List<VersionHistory> versionHistory) {
    this.versionHistory = versionHistory;
  }

  /**
   * @return the version about to be replaced by the next publication (if publication is in progress),
   * or the version that has been replaced by the latest publication (if publication finished).
   */
  @NotNull
  public BigDecimal getReplacedEmlVersion() {
    return (replacedEmlVersion == null) ? Constants.INITIAL_RESOURCE_VERSION : replacedEmlVersion;
  }

  /**
   * Set the replacedEmlVersion. This must match the last published version, if the versionHistory is not empty.
   *
   * @param replacedEmlVersion version to be replace, or that has been replaced
   */
  public void setReplacedEmlVersion(BigDecimal replacedEmlVersion) {
    // safeguard, assuming VersionHistory not empty
    if (!getVersionHistory().isEmpty()) {
      BigDecimal lastVersion = getLastPublishedVersionsVersion();
      if (replacedEmlVersion != null && lastVersion != null && replacedEmlVersion.compareTo(lastVersion) != 0) {
        throw new IllegalArgumentException("Version replaced should be equal to last published version!");
      }
    }
    this.replacedEmlVersion = replacedEmlVersion;
  }

  /**
   * @return true if resource is publicly available, or false otherwise (e.g. it is private or deleted)
   */
  public boolean isPubliclyAvailable() {
    return status.equals(PublicationStatus.PUBLIC) || status.equals(PublicationStatus.REGISTERED);
  }

  /**
   * @return true if the resource citation (EML) should be auto-generated during publication, false otherwise
   */
  public boolean isCitationAutoGenerated() {
    return citationAutoGenerated;
  }

  /**
   * @param citationAutoGenerated true if the citation should be auto-generated during publication, false otherwise
   */
  public void setCitationAutoGenerated(boolean citationAutoGenerated) {
    this.citationAutoGenerated = citationAutoGenerated;
  }

  /**
   * Construct the resource citation from various parts for the version specified.
   * </br>
   * This method is called from the Citation metadata page, in order to preview the resource citation for the upcoming
   * version for example.
   * </br>
   * The citation format is:
   * Creators (PublicationYear): Title. Version. Publisher. ResourceType. Identifier
   *
   * @param version  resource version to use in citation
   * @param homepage homepage URI
   *
   * @return generated resource citation string, or null if it failed to be generated
   */
  public String generateResourceCitation(@NotNull String version, @NotNull String homepage) {
    try {
      return generateResourceCitation(new BigDecimal(version), new URI(homepage));
    } catch (URISyntaxException e) {
      log.error("Failed to generate URI for homepage string: " + homepage, e);
    }
    return null;
  }

  /**
   * Construct the resource citation from various parts for the version specified.
   * </br>
   * The citation format is:
   * Creators (PublicationYear): Title. Version. Publisher. ResourceType. Identifier
   *
   * @param version  resource version to use in citation
   * @param homepage homepage URI
   *
   * @return generated resource citation string
   */
  public String generateResourceCitation(@NotNull BigDecimal version, @NotNull URI homepage) {
    StringBuilder sb = new StringBuilder();

    // make list of verified authors (having first and last name)
    List<String> verifiedAuthorList = Lists.newArrayList();
    for (Agent creator : getEml().getCreators()) {
      String authorName = getAuthorName(creator);
      if (authorName != null) {
        verifiedAuthorList.add(authorName);
      }
    }

    // add comma separated authors
    Iterator<String> iter = verifiedAuthorList.iterator();
    while (iter.hasNext()) {
      sb.append(iter.next());
      if (iter.hasNext()) {
        sb.append(", ");
      }
    }

    // add year resource was first published (captured in EML dateStamp)
    int publicationYear = getPublicationYear(getEml().getDateStamp());
    if (publicationYear > 0) {
      sb.append(" (");
      sb.append(publicationYear);
      sb.append("): ");
    }

    // add title
    sb.append((StringUtils.trimToNull(getTitle()) == null) ? getShortname() : StringUtils.trim(getTitle()));
    sb.append(". ");

    // add version
    sb.append("v");
    sb.append(version.toPlainString());
    sb.append(". ");

    // add publisher
    String publisher = (getOrganisation() == null) ? null : StringUtils.trimToNull(getOrganisation().getName());
    if (publisher != null) {
      sb.append(publisher);
      sb.append(". ");
    }

    // add ResourceTypeGeneral/ResourceType, e.g. Dataset/Occurrence, Dataset/Checklist
    sb.append("Dataset");
    if (getCoreType() != null) {
      sb.append("/");
      sb.append(StringUtils.capitalize(getCoreType().toLowerCase()));
    }
    sb.append(". ");

    // add DOI as the identifier. DataCite recommends using linkable, permanent URL
    if (getDoi() != null) {
      sb.append(getDoi().getUrl());
    }
    // otherwise add the citation identifier instead
    else if (getEml().getCitation() != null && !Strings.isNullOrEmpty(getEml().getCitation().getIdentifier())) {
      sb.append(getEml().getCitation().getIdentifier());
    }
    // otherwise use its IPT homepage as the identifier
    else {
      sb.append(homepage.toString());
    }
    return sb.toString();
  }

  /**
   * Construct author name for citation. Name must have a last name and at least one first name to be included. If
   * both the first and last name are left blank on purpose, the organisation name can be used as an alternative.
   *
   * @param creator creator
   *
   * @return author name
   */
  @VisibleForTesting
  protected String getAuthorName(Agent creator) {
    StringBuilder sb = new StringBuilder();
    String lastName = StringUtils.trimToNull(creator.getLastName());
    String firstNames = StringUtils.trimToNull(creator.getFirstName());
    String organisation = StringUtils.trimToNull(creator.getOrganisation());
    if (lastName != null && firstNames != null) {
      sb.append(lastName);
      sb.append(" ");
      // add first initial of each first name, capitalized
      String[] names = firstNames.split("\\s+");
      for (int i = 0; i < names.length; i++) {
        sb.append(StringUtils.upperCase(String.valueOf(names[i].charAt(0))));
        if (i < names.length - 1) {
          sb.append(" ");
        }
      }
    } else if (lastName == null && firstNames == null && organisation != null) {
      sb.append(organisation);
    }
    return sb.toString();
  }

  /**
   * Get the year from the publication date.
   *
   * @param publicationDate date resource was published
   *
   * @return publication year
   */
  @VisibleForTesting
  protected int getPublicationYear(Date publicationDate) {
    Calendar calendar = Calendar.getInstance();
    calendar.setTime(publicationDate);
    return calendar.get(Calendar.YEAR);
  }

  /**
   * @return the date the metadata was last modified.
   */
  public Date getMetadataModified() {
    return metadataModified;
  }

  /**
   * Set metadataModified date. Update modified date at same time.
   *
   * @param metadataModified date metadata was last modified
   */
  public void setMetadataModified(Date metadataModified) {
    this.modified = metadataModified;
    this.metadataModified = metadataModified;
  }

  /**
   * @return the date any source mapping was last modified.
   */
  public Date getMappingsModified() {
    return mappingsModified;
  }

  /**
   * Set mappingsModified date. Update modified date at same time.
   *
   * @param mappingsModified date mappings were last modified
   */
  public void setMappingsModified(Date mappingsModified) {
    this.modified = mappingsModified;
    this.mappingsModified = mappingsModified;
  }

  /**
   * @return the date any source was last modified.
   */
  public Date getSourcesModified() {
    return sourcesModified;
  }

  /**
   * Set sourcesModified date. Update modified date at same time.
   *
   * @param sourcesModified date sources were last modified
   */
  public void setSourcesModified(Date sourcesModified) {
    this.modified = sourcesModified;
    this.sourcesModified = sourcesModified;
  }

  /**
   * Updates the resource's list of alternate identifiers for the resource's DOI, adding it or removing it depending on
   * the status of the DOI:
   * </br>
   * If the status of the DOI is reserved or public, the resource DOI will be added as the first alternative identifier
   * in the list. Please note that multiple (different) DOIs are allowed in the list of alternate identifiers.
   * </br>
   * If the status of the DOI is unavailable, the resource DOI will be removed from the list.
   */
  public synchronized void updateAlternateIdentifierForDOI() {
    Preconditions.checkNotNull(eml);

    if (doi != null) {
      // retrieve a list of the resource's alternate identifiers
      List<String> ids = eml.getAlternateIdentifiers();
      if (identifierStatus.equals(IdentifierStatus.PUBLIC_PENDING_PUBLICATION) || identifierStatus
        .equals(IdentifierStatus.PUBLIC)) {
        // make sure the DOI always appears first
        List<String> reorderedList = Lists.newArrayList();
        reorderedList.add(doi.toString());
        // make sure the DOI doesn't appear twice
        for (String id : ids) {
          if (!id.equalsIgnoreCase(doi.toString())) {
            reorderedList.add(id);
          }
        }
        // replace the original list with the reordered one
        if (!ids.isEmpty()) {
          ids.clear();
        }
        ids.addAll(reorderedList);
        log.debug("DOI=" + doi.toString() + " added to resource's list of alt ids as first element");
      } else if (identifierStatus.equals(IdentifierStatus.UNAVAILABLE) || identifierStatus
        .equals(IdentifierStatus.UNRESERVED)) {
        for (Iterator<String> iterator = ids.iterator(); iterator.hasNext(); ) {
          String id = iterator.next();
          // make sure a DOI that has been made unavailable, or that has been deleted, no longer appears in the list
          if (id.equalsIgnoreCase(doi.toString())) {
            iterator.remove();
            log.debug("DOI=" + doi.toString() + " removed from resource's list of alt ids");
          }
        }
      }
    }
  }

  /**
   * Updates the resource's citation identifier for the resource's DOI, adding it or removing it depending on
   * the status of the DOI:
   * </br>
   * If the status of the DOI is reserved or public, the resource DOI will be set as the resource citation identifier.
   * </br>
   * If the status of the DOI is unavailable or unreserved, the resource DOI will be unset as the citation identifier.
   */
  public synchronized void updateCitationIdentifierForDOI() {
    Preconditions.checkNotNull(eml);

    if (doi != null) {
      // retrieve resource's citation identifier
      Citation citation = eml.getCitation();
      if (identifierStatus.equals(IdentifierStatus.PUBLIC_PENDING_PUBLICATION) || identifierStatus
        .equals(IdentifierStatus.PUBLIC)) {
        // make sure the DOI set as resource citation identifier
        if (citation == null) {
          // resource must have citation if it has a DOI
          setCitationAutoGenerated(true);
          eml.setCitation(new Citation("Will be replaced by auto-generated citation", doi.getUrl().toString()));
        } else {
          citation.setIdentifier(doi.getUrl().toString());
        }
        log.debug("DOI=" + doi.getUrl().toString() + " set as resource's citation identifier");
      } else if (identifierStatus.equals(IdentifierStatus.UNAVAILABLE) || identifierStatus
        .equals(IdentifierStatus.UNRESERVED)) {
        // make sure the DOI no longer set as resource citation identifier
        if (citation == null) {
          // resource must have had a citation if it had a DOI
          setCitationAutoGenerated(true);
          Citation generated = new Citation();
          generated.setCitation("Will be replaced by auto-generated citation");
          eml.setCitation(generated);
        } else {
          citation.setIdentifier(null);
        }
        log.debug("DOI=" + doi.getUrl().toString() + " unset as resource's citation identifier");
      }
    }
  }

  /**
   * Determine if this resource has at least one mapping to the occurrence core extension, no matter if the mapping
   * is a core or extension mapping.
   *
   * @return true if resource has at least one mapping to the occurrence core extension, false otherwise
   */
  public boolean hasOccurrenceMapping() {
    return !getMappings(Constants.DWC_ROWTYPE_OCCURRENCE).isEmpty();
  }
}