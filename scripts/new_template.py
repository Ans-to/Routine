#!/usr/bin/env python3
"""Scaffolds a new self-contained TemplateProvider stub for Routine without modifying core engine or UI code."""

import argparse
import sys


TEMPLATE_STUB = """package app.routine.feature.templates.contrib

import android.content.Context
import app.routine.core.model.Action
import app.routine.core.model.ActionType
import app.routine.core.model.Rule
import app.routine.core.model.Template
import app.routine.core.model.Trigger
import app.routine.core.model.TriggerType
import app.routine.feature.templates.TemplateProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject

class {class_name} @Inject constructor() : TemplateProvider {{
    override val order: Int = {order}

    override fun provide(context: Context): Template {{
        val rule = Rule(
            id = "rule_{template_id}",
            name = "{title}",
            trigger = Trigger(TriggerType.{trigger_type}),
            actions = listOf(Action(ActionType.{action_type}, mapOf("message" to "{title}"))),
            templateId = "{template_id}",
        )
        return Template(
            id = "{template_id}",
            title = "{title}",
            description = "{description}",
            iconName = "auto_awesome",
            rule = rule,
            requiredTier = rule.requiredTier,
        )
    }}
}}

@Module
@InstallIn(SingletonComponent::class)
abstract class {class_name}Module {{
    @Binds
    @IntoSet
    abstract fun bind(impl: {class_name}): TemplateProvider
}}
"""


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate a self-contained Routine TemplateProvider.")
    parser.add_argument("--id", required=True, help="Unique template slug, e.g. tpl_21_desk_focus")
    parser.add_argument("--class-name", required=True, help="Kotlin class name, e.g. DeskFocusTemplateProvider")
    parser.add_argument("--title", required=True, help="Human-readable title")
    parser.add_argument("--description", required=True, help="Short description")
    parser.add_argument("--order", type=int, default=100)
    parser.add_argument("--trigger", default="CHARGER")
    parser.add_argument("--action", default="TOAST")
    args = parser.parse_args()

    sys.stdout.write(
        TEMPLATE_STUB.format(
            class_name=args.class_name,
            order=args.order,
            template_id=args.id,
            title=args.title,
            description=args.description,
            trigger_type=args.trigger,
            action_type=args.action,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
