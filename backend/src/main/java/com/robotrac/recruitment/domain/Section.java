package com.robotrac.recruitment.domain;

/** The three aptitude sections defined by the Robotrac question bank. */
public enum Section {
    QUANTITATIVE("Quantitative Aptitude"),
    LOGICAL("Logical Reasoning"),
    ANALYTICAL("Analytical Thinking & Problem-Solving");

    private final String label;

    Section(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
