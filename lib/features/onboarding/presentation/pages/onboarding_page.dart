import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

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
                      return Center(
                        child: ConstrainedBox(
                          constraints: const BoxConstraints(maxWidth: 480),
                          child: Padding(
                            padding: EdgeInsets.fromLTRB(
                              24,
                              compact ? 16 : 22,
                              24,
                              compact ? 18 : 24,
                            ),
                            child: _OnboardingContent(
                              state: state,
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
    required this.isIntro,
    required this.compact,
    required this.onContinue,
    required this.onCheckboxChanged,
    required this.onAgree,
    required this.onCancel,
  });

  final OnboardingState state;
  final bool isIntro;
  final bool compact;
  final VoidCallback onContinue;
  final ValueChanged<bool?> onCheckboxChanged;
  final VoidCallback onAgree;
  final VoidCallback onCancel;

  String get _headline {
    if (state.isReadOnly) return 'Notice';
    if (isIntro) return 'Tunnel Forge';
    return 'Review security notice';
  }

  String get _subtitle {
    if (state.isReadOnly) {
      return 'L2TP should only be used when no stronger option is available.';
    }
    if (isIntro) {
      return 'Legacy L2TP support for modern Android, with setup and connection management in one place.';
    }
    return 'L2TP/IPsec is provided for legacy compatibility, is not considered secure by modern standards, and should only be used when stronger protocols are not available.';
  }

  @override
  Widget build(BuildContext context) {
    final textTheme = Theme.of(context).textTheme;
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
          isIntro: isIntro,
          compact: compact,
          titleStyle: titleStyle,
          headline: _headline,
          subtitle: _subtitle,
        ),
        SizedBox(height: compact ? 16 : 20),
        const Spacer(),
        Align(
          alignment: Alignment.bottomLeft,
          child: _NoticePanel(
            state: state,
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
        dot(true),
        const SizedBox(width: 6),
        dot(state.isReadOnly || !isIntro),
      ],
    );
  }
}

class _HeroSection extends StatelessWidget {
  const _HeroSection({
    required this.state,
    required this.isIntro,
    required this.compact,
    required this.titleStyle,
    required this.headline,
    required this.subtitle,
  });

  final OnboardingState state;
  final bool isIntro;
  final bool compact;
  final TextStyle? titleStyle;
  final String headline;
  final String subtitle;

  List<String> _paragraphs() {
    if (state.isReadOnly) {
      return const [
        'L2TP/IPsec no longer meets modern security expectations for sensitive traffic.',
        'Use this option only when compatibility is required and no stronger protocol is available.',
      ];
    }
    if (isIntro) {
      return const [
        'Create and organize profiles, switch between connections, and keep the essentials close at hand.',
        'Designed for environments that still depend on L2TP, without forcing an outdated setup experience.',
        'A focused way to manage legacy VPN access on current Android devices.',
      ];
    }
    return const [
      'This protocol remains available to support older infrastructure, not as a recommended default for new deployments.',
      'It should be treated as easier to decrypt than modern VPN protocols. If your network supports WireGuard, OpenVPN, or another modern alternative, that should be the preferred choice.',
    ];
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
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
    final paragraphs = _paragraphs();
    final brandLineStyle =
        (compact ? textTheme.headlineSmall : textTheme.headlineMedium)
            ?.copyWith(
              color: heroPrimaryTextColor,
              fontWeight: FontWeight.w800,
              letterSpacing: -0.7,
              height: 1.08,
            );
    final supportingTextStyle = textTheme.bodyMedium?.copyWith(
      color: heroSecondaryTextColor,
      height: 1.45,
    );

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
                child: Text(
                  headline,
                  key: const Key('onboarding_title'),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: brandLineStyle,
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
            Padding(
              padding: EdgeInsets.only(right: compact ? 8 : 12),
              child: Text(
                paragraph,
                style: supportingTextStyle?.copyWith(
                  color: isDarkHero
                      ? (isInvertedHero
                            ? const Color(0xFF5F6368)
                            : Colors.white.withValues(alpha: 0.74))
                      : colorScheme.onSurfaceVariant,
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _NoticePanel extends StatelessWidget {
  const _NoticePanel({
    required this.state,
    required this.isIntro,
    required this.compact,
    required this.onCheckboxChanged,
    required this.onContinue,
    required this.onAgree,
    required this.onCancel,
  });

  final OnboardingState state;
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
    final checkboxLabel = isIntro
        ? 'Manage your VPN profiles in one place.'
        : 'I understand the risk and want to continue.';
    final panelTitle = isIntro
        ? null
        : 'Use only when compatibility requires it';
    final panelBody = isIntro
        ? null
        : 'Proceed only if this connection depends on L2TP/IPsec, you understand that it is not a secure modern protocol, and no stronger option is available for the same service.';
    return Column(
      key: const Key('onboarding_countdown'),
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        if (panelTitle != null) ...[
          Text(
            panelTitle,
            style: textTheme.titleMedium?.copyWith(
              fontWeight: FontWeight.w800,
              letterSpacing: -0.2,
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
        if (state.isBlocking && !isIntro) ...[
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
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
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
          onContinue: onContinue,
          onAgree: onAgree,
          onCancel: onCancel,
        ),
      ],
    );
  }
}

class _ActionSection extends StatelessWidget {
  const _ActionSection({
    required this.state,
    required this.isIntro,
    required this.onContinue,
    required this.onAgree,
    required this.onCancel,
  });

  final OnboardingState state;
  final bool isIntro;
  final VoidCallback onContinue;
  final VoidCallback onAgree;
  final VoidCallback onCancel;

  String get _primaryLabel {
    if (isIntro) return 'Continue';
    if (!state.timerComplete) return 'Confirm in ${state.secondsRemaining}s';
    return 'Confirm';
  }

  @override
  Widget build(BuildContext context) {
    final isExiting = state.status == OnboardingFlowStatus.exiting;
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
        !isExiting && (state.isReadOnly || isIntro || state.canAgree);
    void primaryAction() {
      if (isExiting) return;
      if (state.isReadOnly) {
        onCancel();
        return;
      }
      if (isIntro) {
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
                  isIntro
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
                            _primaryLabel,
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
                child: const Text('Dismiss'),
              ),
            ),
          ),
      ],
    );
  }
}
