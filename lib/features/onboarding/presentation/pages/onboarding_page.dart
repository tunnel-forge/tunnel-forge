import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../l10n/app_localizations.dart';
import '../../../../theme.dart';
import '../bloc/onboarding_bloc.dart';

class OnboardingPage extends StatefulWidget {
  const OnboardingPage({super.key});

  @override
  State<OnboardingPage> createState() => _OnboardingPageState();
}

class _OnboardingPageState extends State<OnboardingPage>
    with SingleTickerProviderStateMixin {
  @override
  Widget build(BuildContext context) {
    return BlocBuilder<OnboardingBloc, OnboardingState>(
      builder: (context, state) {
        final isIntro = state.step == OnboardingStep.intro && state.isBlocking;
        final isLanguage =
            state.step == OnboardingStep.language && state.isBlocking;

        return PopScope<void>(
          canPop: state.isReadOnly,
          onPopInvokedWithResult: (didPop, result) {
            if (didPop || !state.isReadOnly) return;
          },
          child: Scaffold(
            body: Stack(
              fit: StackFit.expand,
              children: [
                ColoredBox(color: Theme.of(context).colorScheme.surface),
                SafeArea(
                  child: LayoutBuilder(
                    builder: (context, constraints) {
                      final compact = constraints.maxHeight < 760;
                      final maxContentWidth = isLanguage ? 720.0 : 480.0;
                      return Center(
                        child: ConstrainedBox(
                          constraints: BoxConstraints(
                            maxWidth: maxContentWidth,
                          ),
                          child: Padding(
                            padding: EdgeInsets.fromLTRB(
                              24,
                              compact ? 16 : 22,
                              24,
                              compact ? 18 : 24,
                            ),
                            child: _OnboardingContent(
                              state: state,
                              isLanguage: isLanguage,
                              isIntro: isIntro,
                              compact: compact,
                              onContinue: () => context
                                  .read<OnboardingBloc>()
                                  .add(const OnboardingContinuePressed()),
                              onCheckboxChanged: (value) =>
                                  context.read<OnboardingBloc>().add(
                                    OnboardingCheckboxChanged(value ?? false),
                                  ),
                              onAgree: () => context.read<OnboardingBloc>().add(
                                const OnboardingAgreePressed(),
                              ),
                              onCancel: () => Navigator.of(context).pop(),
                            ),
                          ),
                        ),
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

class _OnboardingContent extends StatelessWidget {
  const _OnboardingContent({
    required this.state,
    required this.isLanguage,
    required this.isIntro,
    required this.compact,
    required this.onContinue,
    required this.onCheckboxChanged,
    required this.onAgree,
    required this.onCancel,
  });

  final OnboardingState state;
  final bool isLanguage;
  final bool isIntro;
  final bool compact;
  final VoidCallback onContinue;
  final ValueChanged<bool?> onCheckboxChanged;
  final VoidCallback onAgree;
  final VoidCallback onCancel;

  String _headline(AppLocalizations t) {
    if (state.isReadOnly) return t.notice;
    if (isLanguage) return t.chooseLanguage;
    if (isIntro) return t.appTitle;
    return t.securityNoticeTitle;
  }

  String _subtitle(AppLocalizations t) {
    if (state.isReadOnly) {
      return t.securityReadOnlySubtitle;
    }
    if (isLanguage) {
      return t.languageSubtitle;
    }
    if (isIntro) {
      return t.onboardingIntroSubtitle;
    }
    return t.securityNoticeSubtitle;
  }

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
    final t = AppLocalizations.of(context);
    final titleStyle = compact
        ? textTheme.headlineLarge
        : textTheme.displayMedium;

    return Column(
      children: [
        if (!state.isReadOnly) ...[
          _StepIndicatorRow(state: state, isIntro: isIntro),
          SizedBox(height: compact ? 14 : 18),
        ],
        _HeroSection(
          state: state,
          isLanguage: isLanguage,
          isIntro: isIntro,
          compact: compact,
          titleStyle: titleStyle,
          headline: _headline(t),
          subtitle: _subtitle(t),
        ),
        SizedBox(height: compact ? 16 : 20),
        const Spacer(),
        Align(
          alignment: Alignment.bottomLeft,
          child: _NoticePanel(
            state: state,
            isLanguage: isLanguage,
            isIntro: isIntro,
            compact: compact,
            onCheckboxChanged: onCheckboxChanged,
            onContinue: onContinue,
            onAgree: onAgree,
            onCancel: onCancel,
          ),
        ),
      ],
    );
  }
}

class _StepIndicatorRow extends StatelessWidget {
  const _StepIndicatorRow({required this.state, required this.isIntro});

  final OnboardingState state;
  final bool isIntro;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final activeColor = colorScheme.onSurface;
    final inactiveColor = colorScheme.onSurface.withValues(alpha: 0.28);

    Widget dot(bool active) {
      return AnimatedContainer(
        duration: const Duration(milliseconds: 220),
        width: active ? 18 : 6,
        height: 6,
        decoration: BoxDecoration(
          color: active ? activeColor : inactiveColor,
          borderRadius: BorderRadius.circular(999),
        ),
      );
    }

    return Row(
      children: [
        const Spacer(),
        dot(state.step == OnboardingStep.language),
        const SizedBox(width: 6),
        dot(state.step == OnboardingStep.intro),
        const SizedBox(width: 6),
        dot(state.isReadOnly || state.step == OnboardingStep.acknowledgement),
      ],
    );
  }
}

class _HeroSection extends StatelessWidget {
  const _HeroSection({
    required this.state,
    required this.isLanguage,
    required this.isIntro,
    required this.compact,
    required this.titleStyle,
    required this.headline,
    required this.subtitle,
  });

  final OnboardingState state;
  final bool isLanguage;
  final bool isIntro;
  final bool compact;
  final TextStyle? titleStyle;
  final String headline;
  final String subtitle;

  List<String> _paragraphs(AppLocalizations t) {
    if (state.isReadOnly) {
      return [t.securityReadOnlyP1, t.securityReadOnlyP2];
    }
    if (isLanguage) {
      return [t.languageBody];
    }
    if (isIntro) {
      return [t.onboardingIntroP1, t.onboardingIntroP2, t.onboardingIntroP3];
    }
    return [t.securityP1, t.securityP2];
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final t = AppLocalizations.of(context);
    final textTheme = Theme.of(context).textTheme;
    final brightness = Theme.of(context).brightness;
    final isDarkHero = state.isBlocking;
    final isInvertedHero = isDarkHero && brightness == Brightness.dark;
    final heroBackgroundColor = state.isReadOnly
        ? Colors.transparent
        : isDarkHero
        ? (isInvertedHero ? Colors.white : const Color(0xFF111315))
        : colorScheme.surfaceContainer;
    final heroPrimaryTextColor = state.isReadOnly
        ? colorScheme.onSurface
        : isDarkHero
        ? (isInvertedHero ? Colors.black : Colors.white)
        : colorScheme.onSurface;
    final heroSecondaryTextColor = state.isReadOnly
        ? colorScheme.onSurfaceVariant
        : isDarkHero
        ? (isInvertedHero
              ? const Color(0xFF4A4F55)
              : Colors.white.withValues(alpha: 0.84))
        : colorScheme.onSurfaceVariant;
    final paragraphs = _paragraphs(t);
    final brandLineStyle =
        (compact ? textTheme.headlineSmall : textTheme.headlineMedium)
            ?.copyWith(
              color: heroPrimaryTextColor,
              fontWeight: FontWeight.w700,
              letterSpacing: 0,
              height: 1.15,
            );
    final supportingTextStyle = textTheme.bodyMedium?.copyWith(
      color: heroSecondaryTextColor,
      height: 1.45,
    );
    final isBrandHeadline = headline == t.appTitle;

    return Container(
      width: double.infinity,
      padding: state.isReadOnly
          ? EdgeInsets.zero
          : EdgeInsets.all(compact ? 20 : 24),
      decoration: BoxDecoration(
        color: heroBackgroundColor,
        borderRadius: BorderRadius.circular(state.isReadOnly ? 0 : 28),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Directionality(
                  textDirection: isBrandHeadline
                      ? TextDirection.ltr
                      : Directionality.of(context),
                  child: Text(
                    headline,
                    key: const Key('onboarding_title'),
                    textAlign: isBrandHeadline ? TextAlign.left : null,
                    style: brandLineStyle,
                  ),
                ),
              ),
            ],
          ),
          SizedBox(height: compact ? 12 : 14),
          Row(
            children: [
              Expanded(child: Text(subtitle, style: supportingTextStyle)),
            ],
          ),
          for (final paragraph in paragraphs) ...[
            SizedBox(height: compact ? 12 : 14),
            Text(paragraph, style: supportingTextStyle),
          ],
        ],
      ),
    );
  }
}

class _NoticePanel extends StatelessWidget {
  const _NoticePanel({
    required this.state,
    required this.isLanguage,
    required this.isIntro,
    required this.compact,
    required this.onCheckboxChanged,
    required this.onContinue,
    required this.onAgree,
    required this.onCancel,
  });

  final OnboardingState state;
  final bool isLanguage;
  final bool isIntro;
  final bool compact;
  final ValueChanged<bool?> onCheckboxChanged;
  final VoidCallback onContinue;
  final VoidCallback onAgree;
  final VoidCallback onCancel;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;
    final t = AppLocalizations.of(context);
    final languageController = isLanguage
        ? AppLanguageController.of(context)
        : null;
    final checkboxLabel = isIntro ? t.introCheckbox : t.riskCheckbox;
    final panelTitle = isIntro
        ? null
        : (isLanguage ? null : t.compatibilityOnly);
    final panelBody = isIntro
        ? null
        : (isLanguage ? null : t.compatibilityBody);
    return Column(
      key: const Key('onboarding_countdown'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (panelTitle != null) ...[
          Text(
            panelTitle,
            style: textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.w700,
              letterSpacing: 0,
            ),
          ),
          const SizedBox(height: 10),
        ],
        if (panelBody != null) ...[
          Text(
            panelBody,
            style: textTheme.bodyMedium?.copyWith(
              color: colorScheme.onSurfaceVariant,
              height: 1.35,
            ),
          ),
          SizedBox(height: compact ? 18 : 22),
        ],
        if (isLanguage) ...[
          _LanguageChoiceList(
            selectedLanguage: languageController!.language,
            onLanguageChanged: languageController.setLanguage,
          ),
        ],
        if (state.isBlocking && !isIntro && !isLanguage) ...[
          Material(
            color: Colors.transparent,
            child: InkWell(
              borderRadius: BorderRadius.circular(14),
              onTap: () => onCheckboxChanged(!state.checkboxChecked),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 2),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    SizedBox(
                      width: 24,
                      height: 24,
                      child: Checkbox(
                        key: const Key('onboarding_checkbox'),
                        value: state.checkboxChecked,
                        onChanged: onCheckboxChanged,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(5),
                        ),
                        visualDensity: VisualDensity.compact,
                        materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                      ),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        checkboxLabel,
                        style: textTheme.bodyMedium?.copyWith(
                          height: 1.0,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
        SizedBox(height: compact ? 16 : 20),
        _ActionSection(
          state: state,
          isIntro: isIntro,
          isLanguage: isLanguage,
          onContinue: onContinue,
          onAgree: onAgree,
          onCancel: onCancel,
        ),
      ],
    );
  }
}

class _LanguageChoiceList extends StatelessWidget {
  const _LanguageChoiceList({
    required this.selectedLanguage,
    required this.onLanguageChanged,
  });

  final AppLanguage selectedLanguage;
  final ValueChanged<AppLanguage> onLanguageChanged;

  @override
  Widget build(BuildContext context) {
    final t = AppLocalizations.of(context);

    return Column(
      children: [
        _LanguageChoiceTile(
          language: AppLanguage.english,
          title: t.english,
          selected: selectedLanguage == AppLanguage.english,
          onTap: () => onLanguageChanged(AppLanguage.english),
        ),
        const SizedBox(height: 12),
        _LanguageChoiceTile(
          language: AppLanguage.persian,
          title: t.persian,
          selected: selectedLanguage == AppLanguage.persian,
          onTap: () => onLanguageChanged(AppLanguage.persian),
        ),
      ],
    );
  }
}

class _LanguageChoiceTile extends StatelessWidget {
  const _LanguageChoiceTile({
    required this.language,
    required this.title,
    required this.selected,
    required this.onTap,
  });

  final AppLanguage language;
  final String title;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final semanticColors =
        Theme.of(context).extension<AppSemanticColors>() ??
        AppSemanticColors.fallback(Theme.of(context).brightness);
    final borderColor = selected
        ? semanticColors.connected
        : colorScheme.outlineVariant.withValues(alpha: 0.72);
    final backgroundColor = selected
        ? semanticColors.connected.withValues(alpha: 0.10)
        : colorScheme.surfaceContainerHighest.withValues(alpha: 0.42);
    final direction = language == AppLanguage.persian
        ? TextDirection.rtl
        : TextDirection.ltr;

    return Semantics(
      button: true,
      selected: selected,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(18),
          child: AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 15),
            decoration: BoxDecoration(
              color: backgroundColor,
              borderRadius: BorderRadius.circular(18),
              border: Border.all(color: borderColor, width: selected ? 2 : 1),
            ),
            child: Directionality(
              textDirection: direction,
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      title,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                  ),
                  const SizedBox(width: 16),
                  AnimatedContainer(
                    duration: const Duration(milliseconds: 180),
                    width: 26,
                    height: 26,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: selected
                          ? semanticColors.connected
                          : Colors.transparent,
                      border: Border.all(
                        color: selected
                            ? semanticColors.connected
                            : colorScheme.outline,
                        width: 2,
                      ),
                    ),
                    child: selected
                        ? Icon(
                            Icons.check,
                            size: 17,
                            color: semanticColors.onConnected,
                          )
                        : null,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ActionSection extends StatelessWidget {
  const _ActionSection({
    required this.state,
    required this.isLanguage,
    required this.isIntro,
    required this.onContinue,
    required this.onAgree,
    required this.onCancel,
  });

  final OnboardingState state;
  final bool isLanguage;
  final bool isIntro;
  final VoidCallback onContinue;
  final VoidCallback onAgree;
  final VoidCallback onCancel;

  String _primaryLabel(AppLocalizations t) {
    if (isLanguage || isIntro) return t.continueLabel;
    if (!state.timerComplete) return t.confirmInSeconds(state.secondsRemaining);
    return t.confirm;
  }

  @override
  Widget build(BuildContext context) {
    final isExiting = state.status == OnboardingFlowStatus.exiting;
    final t = AppLocalizations.of(context);
    final brightness = Theme.of(context).brightness;
    final semanticColors =
        Theme.of(context).extension<AppSemanticColors>() ??
        AppSemanticColors.fallback(Theme.of(context).brightness);
    final disabledButtonColor = brightness == Brightness.light
        ? const Color(0xFFE1E6EB)
        : const Color(0xFF2B333B);
    final disabledLabelColor = brightness == Brightness.light
        ? const Color(0xFF5F6B77)
        : const Color(0xFFB4BEC8);
    final isVisuallyEnabled =
        !isExiting &&
        (state.isReadOnly || isLanguage || isIntro || state.canAgree);
    void primaryAction() {
      if (isExiting) return;
      if (state.isReadOnly) {
        onCancel();
        return;
      }
      if (isLanguage || isIntro) {
        onContinue();
        return;
      }
      if (state.canAgree) {
        onAgree();
      }
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (!state.isReadOnly)
          Semantics(
            button: true,
            enabled: isVisuallyEnabled,
            child: Material(
              color: Colors.transparent,
              child: InkWell(
                key: Key(
                  isLanguage || isIntro
                      ? 'onboarding_continue_button'
                      : 'onboarding_agree_button',
                ),
                onTap: primaryAction,
                borderRadius: BorderRadius.circular(18),
                splashColor: Colors.transparent,
                highlightColor: Colors.transparent,
                overlayColor: const WidgetStatePropertyAll(Colors.transparent),
                child: Ink(
                  height: 56,
                  decoration: BoxDecoration(
                    color: isVisuallyEnabled
                        ? semanticColors.connected
                        : disabledButtonColor,
                    borderRadius: BorderRadius.circular(18),
                  ),
                  child: Center(
                    child: isExiting
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : Text(
                            _primaryLabel(t),
                            style: Theme.of(context).textTheme.labelLarge
                                ?.copyWith(
                                  color: isVisuallyEnabled
                                      ? semanticColors.onConnected
                                      : disabledLabelColor,
                                ),
                          ),
                  ),
                ),
              ),
            ),
          ),
        if (state.isReadOnly)
          Padding(
            padding: const EdgeInsets.only(top: 4),
            child: SizedBox(
              width: double.infinity,
              child: TextButton(
                key: const Key('onboarding_cancel_button'),
                onPressed: isExiting ? null : onCancel,
                style: TextButton.styleFrom(
                  overlayColor: Colors.transparent,
                  animationDuration: Duration.zero,
                  minimumSize: const Size.fromHeight(52),
                  alignment: Alignment.center,
                ),
                child: Text(t.dismiss),
              ),
            ),
          ),
      ],
    );
  }
}
