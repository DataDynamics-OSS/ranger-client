package io.datadynamics.ranger.client.model;

/** Fields shared by Ranger Admin objects ({@code RangerBaseModelObject}). */
public interface RangerBaseObject {
    Long getId();

    String getGuid();

    Boolean isEnabled();

    Long getVersion();
}
