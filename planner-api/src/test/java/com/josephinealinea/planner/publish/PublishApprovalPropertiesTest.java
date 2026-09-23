package com.josephinealinea.planner.publish;

import com.josephinealinea.planner.publish.api.PublishApprovalProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PublishApprovalPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PublishApprovalProperties.Registration.class);

    @Test
    void owner_approval_is_required_by_default() {
        runner.run(context -> assertThat(
                context.getBean(PublishApprovalProperties.class).requireOwnerApproval()).isTrue());
    }

    @Test
    void true_keeps_the_request_flow() {
        runner.withPropertyValues("app.publish.require-owner-approval=true")
                .run(context -> assertThat(
                        context.getBean(PublishApprovalProperties.class).requireOwnerApproval()).isTrue());
    }

    @Test
    void false_lets_any_member_publish() {
        runner.withPropertyValues("app.publish.require-owner-approval=false")
                .run(context -> assertThat(
                        context.getBean(PublishApprovalProperties.class).requireOwnerApproval()).isFalse());
    }
}
